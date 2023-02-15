package ca.hec.jobs.impl.tools;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import ca.hec.jobs.impl.AbstractQuartzJobImpl;
import lombok.Setter;

import org.sakaiproject.db.api.SqlService;

/**
 * Deletes all submissions in Assignments and Samigo tools, while keeping the assessments themselves.
 * 
 * @author Curtis van Osch
 */
public class DeleteAssignmentAndTestSubmissions extends AbstractQuartzJobImpl {

    /**
     * Our logger
     */
    private static Log log = LogFactory
	    .getLog(DeleteAssignmentAndTestSubmissions.class);

	@Setter
	private SqlService sqlService;

	// prevent multiple instances
	static private boolean isRunning = false;

	// unless specified in parameters, don't actually delete anything
	private boolean debugMode;

	public void execute(JobExecutionContext context) throws JobExecutionException {
		debugMode = true;

		if (isRunning) {
			log.error("Job already running");
			return;
		}
		else { 	isRunning = true; }

		Connection c = null;
		try {
			long start = System.currentTimeMillis();
			log.info("starting");

			List<String> allSites = null;
			String term = context.getMergedJobDataMap().getString("deleteSubmission.term");
			String siteIds = context.getMergedJobDataMap().getString("sites");

			if (context.getMergedJobDataMap().getString("debugMode").equals("DELETE")) {
				debugMode = false;
			}

			if (!siteIds.isEmpty()) {
				allSites = Arrays.asList(siteIds.split(","));
			}
			else if (!term.isEmpty()) {
				Map<String, String> props = new HashMap<String,String>();
				props.put("term", term);
				allSites = siteService.getSiteIds(SiteService.SelectionType.ANY, "course", null, props, null, 
					SiteService.SortType.NONE, null, null);
			}
			else {
				log.error("Must specify either siteId(s) or session.");
				return;
			}

			log.info("Delete submissions from " + allSites.size() + " sites");
			if (debugMode) {
				log.info("************ Debug mode active, only printing logs (no delete) ******************");
			}

			// get a database connection
			c = sqlService.borrowConnection();
			c.setAutoCommit(false); // let us commit after each site

			List<PreparedStatement> deleteAssignmentStatements = new ArrayList<PreparedStatement>();
			List<PreparedStatement> deleteTestAndQuizStatements = new ArrayList<PreparedStatement>();
			try {
				createAssignmentStatements(deleteAssignmentStatements, c);
				createTestAndQuizStatements(deleteTestAndQuizStatements, c);	
			}
			catch (SQLException e) {
				log.error("Problem creating PreparedStatements");
				e.printStackTrace();
				return;
			}

			int assignmentCount = 0, samigoCount = 0;
			for (int i = 0; i < allSites.size(); i++) {

				String siteId = allSites.get(i);

				log.debug("Delete submissions for " + siteId);
				try {
					Site site = siteService.getSite(siteId);

					if (!site.getTools("sakai.assignment.grades").isEmpty()) {
						List<String> ids = sqlService.dbRead("SELECT submission_id FROM ASN_SUBMISSION " +
							"where ASSIGNMENT_ID IN (SELECT ASSIGNMENT_ID FROM ASN_ASSIGNMENT aa WHERE CLOSE_DATE < TRUNC(SYSDATE-(18*30)) and CONTEXT = '" + siteId + "')");
						if (ids.size() > 0) {
							assignmentCount += ids.size();
							log.debug(String.format("Delete %d assignment submissions.", ids.size()));
							deleteSubmissions(siteId, deleteAssignmentStatements);	
						}
					}
					if (!site.getTools("sakai.samigo").isEmpty()) {
						List<String> ids = sqlService.dbRead("select assessmentgradingid FROM SAM_ASSESSMENTGRADING_T sat " + 
						"WHERE SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
						"AND publishedassessmentid IN (SELECT qualifierid FROM SAM_AUTHZDATA_T sat2 WHERE FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' AND sat2.AGENTID = '" + siteId + "')");
						if (ids.size() > 0) {
							samigoCount += ids.size();
							log.debug(String.format("Delete %d Samigo submissions.", ids.size()));
							deleteSubmissions(siteId, deleteTestAndQuizStatements);	
						}
					}
					c.commit();
				} catch (Exception e) {
					e.printStackTrace();
					c.rollback();
				}
			}
			log.debug(String.format("Removed %d assignment submissions and %d test & quiz submissions total.", assignmentCount, samigoCount));
			log.info("Completed in " + (System.currentTimeMillis() - start) + " ms");

		}
		catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			isRunning = false;
			if (c != null) {
				try { c.close(); }
				catch (Exception e) { 
					log.error("Error closing connection"); 
					e.printStackTrace(); 
				}
			}
		}
	} // execute
	
	private void createTestAndQuizStatements(List<PreparedStatement> statements, Connection c) throws SQLException {
		statements.add(c.prepareStatement(
			"DELETE FROM CONTENT_RESOURCE cr WHERE RESOURCE_ID IN (" +
			"SELECT '/private/samigo/' || sat2.AGENTID || '/' || spt.ID || '/' || sit.AGENTID || '/' || sit.PUBLISHEDITEMID || '' || smt.FILENAME " +
			"FROM SAM_MEDIA_T smt, SAM_ITEMGRADING_T sit, SAM_ASSESSMENTGRADING_T sat, SAM_AUTHZDATA_T sat2, SAKAI_USER_ID_MAP suim , SAM_PUBLISHEDASSESSMENT_T spt " +
			"WHERE spt.ID = publishedassessmentid AND suim.USER_ID = sat.AGENTID AND smt.ITEMGRADINGID = sit.ITEMGRADINGID AND sit.ASSESSMENTGRADINGID = sat.ASSESSMENTGRADINGID AND publishedassessmentid = qualifierid " +
				"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
				"AND FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' AND sat2.AGENTID = ?)"));
		
		statements.add(c.prepareStatement(
			"DELETE FROM CONTENT_RESOURCE_BODY_BINARY WHERE RESOURCE_ID IN ( " +
			"SELECT '/private/samigo/' || sat2.AGENTID || '/' || spt.ID || '/' || sit.AGENTID || '/' || sit.PUBLISHEDITEMID || '' || smt.FILENAME " +
				"FROM SAM_MEDIA_T smt, SAM_ITEMGRADING_T sit, SAM_ASSESSMENTGRADING_T sat, SAM_AUTHZDATA_T sat2, SAKAI_USER_ID_MAP suim , SAM_PUBLISHEDASSESSMENT_T spt " +
				"WHERE spt.ID = publishedassessmentid AND suim.USER_ID = sat.AGENTID AND smt.ITEMGRADINGID = sit.ITEMGRADINGID AND sit.ASSESSMENTGRADINGID = sat.ASSESSMENTGRADINGID AND publishedassessmentid = qualifierid " +
				"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
					"AND FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' AND sat2.AGENTID = ?)"));

		statements.add(c.prepareStatement(
			"DELETE FROM SAM_MEDIA_T smt WHERE ITEMGRADINGID IN " +
			"(SELECT sit.ITEMGRADINGID FROM SAM_ITEMGRADING_T sit, SAM_ASSESSMENTGRADING_T sat, SAM_AUTHZDATA_T sat2 " +
			"WHERE sit.ASSESSMENTGRADINGID = sat.ASSESSMENTGRADINGID AND publishedassessmentid = qualifierid AND FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' " + 
			"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
			"AND sat2.AGENTID = ?)"));

		statements.add(c.prepareStatement(
			"DELETE FROM CONTENT_RESOURCE WHERE RESOURCE_ID IN " +
			"(SELECT sgt.RESOURCEID FROM SAM_GRADINGATTACHMENT_T sgt, SAM_ITEMGRADING_T sigt, SAM_ASSESSMENTGRADING_T sat, SAM_AUTHZDATA_T sat2 " +
			"WHERE sgt.ITEMGRADINGID = sigt.ITEMGRADINGID AND sigt.ASSESSMENTGRADINGID = sat.ASSESSMENTGRADINGID AND publishedassessmentid = qualifierid AND FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' " +
			"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
			"AND sat2.AGENTID = ?)"));
		
		statements.add(c.prepareStatement(
			"DELETE FROM CONTENT_RESOURCE_BODY_BINARY WHERE RESOURCE_ID IN " +
			"(SELECT sgt.RESOURCEID FROM SAM_GRADINGATTACHMENT_T sgt, SAM_ITEMGRADING_T sigt, SAM_ASSESSMENTGRADING_T sat, SAM_AUTHZDATA_T sat2 " + 
			"WHERE sgt.ITEMGRADINGID = sigt.ITEMGRADINGID AND sigt.ASSESSMENTGRADINGID = sat.ASSESSMENTGRADINGID AND publishedassessmentid = qualifierid AND FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' " +
			"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
			"AND sat2.AGENTID = ?)"));

		statements.add(c.prepareStatement(
			"DELETE FROM SAM_GRADINGATTACHMENT_T sgt WHERE ITEMGRADINGID IN " +
			"(SELECT sigt.ITEMGRADINGID FROM SAM_ITEMGRADING_T sigt, SAM_ASSESSMENTGRADING_T sat, SAM_AUTHZDATA_T sat2 " +
			"WHERE sigt.ASSESSMENTGRADINGID = sat.ASSESSMENTGRADINGID AND publishedassessmentid = qualifierid AND FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' " +
			"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
			"AND sat2.AGENTID = ?)"));

		statements.add(c.prepareStatement(
			"DELETE FROM SAM_ITEMGRADING_T sit WHERE ASSESSMENTGRADINGID IN " +
			"(SELECT ASSESSMENTGRADINGID FROM SAM_ASSESSMENTGRADING_T sat, SAM_AUTHZDATA_T sat2 WHERE publishedassessmentid = qualifierid AND FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' " +
			"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
			"AND sat2.AGENTID = ?)"));

		// Delete resources and GradingAttachments for AssessmentGrading
		statements.add(c.prepareStatement(
			"DELETE FROM CONTENT_RESOURCE WHERE RESOURCE_ID IN ( " +
			"SELECT sgt.RESOURCEID FROM SAM_GRADINGATTACHMENT_T sgt, SAM_ASSESSMENTGRADING_T sat, SAM_AUTHZDATA_T sat2 " + 
			"WHERE sgt.ASSESSMENTGRADINGID = sat.ASSESSMENTGRADINGID "+
			"AND sat.publishedassessmentid = sat2.qualifierid "+
			"AND sat2.FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' "+
			"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
			"AND sat2.AGENTID = ?)"));

		statements.add(c.prepareStatement(
			"DELETE FROM CONTENT_RESOURCE_BODY_BINARY WHERE RESOURCE_ID IN ( "+
			"SELECT sgt.RESOURCEID FROM SAM_GRADINGATTACHMENT_T sgt, SAM_ASSESSMENTGRADING_T sat, SAM_AUTHZDATA_T sat2 "+
			"WHERE sgt.ASSESSMENTGRADINGID = sat.ASSESSMENTGRADINGID "+
			"AND sat.publishedassessmentid = sat2.qualifierid "+
			"AND sat2.FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' "+
			"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
			"AND sat2.AGENTID = ?)"));

		statements.add(c.prepareStatement("DELETE FROM SAM_GRADINGATTACHMENT_T sgt WHERE ASSESSMENTGRADINGID IN "+
			"(SELECT sat.ASSESSMENTGRADINGID "+
			"FROM SAM_ASSESSMENTGRADING_T sat, SAM_AUTHZDATA_T sat2 "+
			"WHERE sat.publishedassessmentid = sat2.qualifierid AND FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' "+
			"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) " +
			"AND sat2.AGENTID = ?)"));

		statements.add(c.prepareStatement("DELETE FROM SAM_ASSESSMENTGRADING_T sat " +
		"WHERE publishedassessmentid IN (SELECT qualifierid FROM SAM_AUTHZDATA_T sat2 WHERE FUNCTIONID = 'OWN_PUBLISHED_ASSESSMENT' AND sat2.AGENTID = ?)" +
		"AND sat.SUBMITTEDDATE < TRUNC(SYSDATE-18*30) "));
	}

	private void createAssignmentStatements(List<PreparedStatement> statements, Connection c) throws SQLException {
		// delete from contentreview
		statements.add(c.prepareStatement(
			"DELETE FROM CONTENTREVIEW_ITEM_PROPERTIES WHERE CONTENTREVIEW_ITEM_ID IN " +
			"(SELECT cri.ID FROM CONTENTREVIEW_ITEM cri WHERE SITEID = ?)"));
		statements.add(c.prepareStatement(
			"DELETE FROM CONTENTREVIEW_ITEM cri WHERE SITEID = ?"));

		// delete attachments and content in resources tool
		statements.add(c.prepareStatement(
			"DELETE FROM CONTENT_RESOURCE_BODY_BINARY WHERE RESOURCE_ID IN " +
			"(SELECT substr(attachment, 9) FROM ASN_SUBMISSION_ATTACHMENTS asa, ASN_SUBMISSION asub, ASN_ASSIGNMENT ass " +
			"WHERE asa.SUBMISSION_ID = asub.SUBMISSION_ID AND asub.ASSIGNMENT_ID = ass.ASSIGNMENT_ID " + 
			"AND ass.CLOSE_DATE < TRUNC(SYSDATE-(18*30)) AND ass.CONTEXT = ?)"));
		statements.add(c.prepareStatement(
			"DELETE FROM CONTENT_RESOURCE WHERE RESOURCE_ID IN " +
			"(SELECT substr(attachment, 9) FROM ASN_SUBMISSION_ATTACHMENTS asa, ASN_SUBMISSION asub, ASN_ASSIGNMENT ass " +
			"WHERE asa.SUBMISSION_ID = asub.SUBMISSION_ID AND asub.ASSIGNMENT_ID = ass.ASSIGNMENT_ID " +
			"AND ass.CLOSE_DATE < TRUNC(SYSDATE-(18*30)) AND ass.CONTEXT = ?)"));
		statements.add(c.prepareStatement(
			"DELETE FROM ASN_SUBMISSION_ATTACHMENTS WHERE SUBMISSION_ID IN " +
			"(SELECT asub.SUBMISSION_ID from ASN_SUBMISSION asub, ASN_ASSIGNMENT ass " +
			"WHERE asub.ASSIGNMENT_ID = ass.ASSIGNMENT_ID " +
			"AND ass.CLOSE_DATE < TRUNC(SYSDATE-(18*30)) AND ass.CONTEXT = ?)"));

		// delete feedback and attachments
		statements.add(c.prepareStatement(
			"DELETE FROM CONTENT_RESOURCE_BODY_BINARY WHERE RESOURCE_ID IN " +
			"(SELECT substr(feedback_attachment, 9) FROM ASN_SUBMISSION_FEEDBACK_ATTACH asfa, ASN_SUBMISSION asub, ASN_ASSIGNMENT ass " +
			"WHERE asfa.SUBMISSION_ID = asub.SUBMISSION_ID AND asub.ASSIGNMENT_ID = ass.ASSIGNMENT_ID " +
			"AND ass.CLOSE_DATE < TRUNC(SYSDATE-(18*30)) AND ass.CONTEXT = ?)"));			
		statements.add(c.prepareStatement(
			"DELETE FROM CONTENT_RESOURCE WHERE RESOURCE_ID IN " +
			"(SELECT substr(feedback_attachment, 9) FROM ASN_SUBMISSION_FEEDBACK_ATTACH asfa, ASN_SUBMISSION asub, ASN_ASSIGNMENT ass " +
			"WHERE asfa.SUBMISSION_ID = asub.SUBMISSION_ID AND asub.ASSIGNMENT_ID = ass.ASSIGNMENT_ID " +
			"AND ass.CLOSE_DATE < TRUNC(SYSDATE-(18*30)) AND ass.CONTEXT = ?)"));
		statements.add(c.prepareStatement(
			"DELETE FROM ASN_SUBMISSION_FEEDBACK_ATTACH asfa WHERE SUBMISSION_ID IN " +
			"(SELECT asub.SUBMISSION_ID FROM ASN_SUBMISSION asub, ASN_ASSIGNMENT ass " +
			"WHERE asub.ASSIGNMENT_ID = ass.ASSIGNMENT_ID " +
			"AND ass.CLOSE_DATE < TRUNC(SYSDATE-(18*30)) AND ass.CONTEXT = ?)"));

		// delete submissions 
		statements.add(c.prepareStatement(
			"DELETE FROM ASN_SUBMISSION_SUBMITTER WHERE SUBMISSION_ID IN " +
			"(SELECT asub.SUBMISSION_ID FROM ASN_SUBMISSION asub, ASN_ASSIGNMENT ass WHERE asub.ASSIGNMENT_ID = ass.ASSIGNMENT_ID " +
			"AND ass.CLOSE_DATE < TRUNC(SYSDATE-(18*30)) AND ass.CONTEXT = ?)"));
		statements.add(c.prepareStatement(
			"DELETE FROM ASN_SUBMISSION_PROPERTIES WHERE SUBMISSION_ID IN " +
			"(SELECT asub.SUBMISSION_ID FROM ASN_SUBMISSION asub, ASN_ASSIGNMENT ass WHERE asub.ASSIGNMENT_ID = ass.ASSIGNMENT_ID " +
			"AND ass.CLOSE_DATE < TRUNC(SYSDATE-(18*30)) AND ass.CONTEXT = ?)"));
		statements.add(c.prepareStatement(
			"DELETE FROM ASN_SUBMISSION WHERE ASSIGNMENT_ID IN (SELECT ASSIGNMENT_ID FROM ASN_ASSIGNMENT WHERE CLOSE_DATE < TRUNC(SYSDATE-(18*30)) AND CONTEXT = ?)"));
}

	private void deleteSubmissions(String siteId, List<PreparedStatement> statements) throws SQLException {
		if (!debugMode) {
			for (int j = 0; j < statements.size(); j++) {
				statements.get(j).setString(1, siteId);
				statements.get(j).execute();
			}
		}
	}
}
