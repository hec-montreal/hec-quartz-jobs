package ca.hec.jobs.impl.site;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.db.api.SqlReader;
import org.sakaiproject.db.api.SqlReaderFinishedException;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.email.api.EmailService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.UserDirectoryService;

import ca.hec.api.SiteIdFormatHelper;
import ca.hec.jobs.api.site.RemovePastSubmissions;
import lombok.Setter;

public class RemovePastSubmissionsImpl implements RemovePastSubmissions {

	@Setter
	private SqlService sqlService;
	@Setter
	protected SessionManager sessionManager;
	@Setter
	protected ServerConfigurationService serverConfigService;
    @Setter
    protected SiteService siteService;


	private static Log log = LogFactory.getLog(RemovePastSubmissionsImpl.class);
	private static boolean isRunning = false;


	@Override
	public void execute(JobExecutionContext context) throws JobExecutionException {
		
		 if (isRunning) {
	            log.error("Job already running!");
	            return;
	        }

	        isRunning = true;

	    
		String subFromStartDateString = context.getMergedJobDataMap().getString("subFromStartDate");
		String subFromEndDateString = context.getMergedJobDataMap().getString("subFromEndDate");
		String specificSites = context.getMergedJobDataMap().getString("siteIdsSpec");
		
		List<String> selectedSites = null;
		String siteIds = null;

		if (!StringUtils.isEmpty(specificSites)) {
			specificSites = specificSites.replaceAll(" ", "");
			selectedSites = new ArrayList<String>(Arrays.asList(specificSites.split(",")));
			siteIds = "'" + String.join("','", selectedSites) + "'";
		} else if (!(StringUtils.isEmpty(subFromEndDateString) && StringUtils.isEmpty(subFromStartDateString))) {
			Date subFromStartDate = null;
			Date subFromEndDate = null;

			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
			try {
				subFromStartDate = dateFormat.parse(subFromStartDateString);
				subFromEndDate = dateFormat.parse(subFromEndDateString);
			} catch (ParseException e) {
				log.debug("Les formats des dates ne sont pas corrects ou une des dates manque");
				return;
			}
			selectedSites = getSelectedSites(subFromStartDateString, subFromEndDateString);
			log.debug(" Les sites de cours officiels créés entre " + subFromStartDateString + " et " +  subFromEndDateString + " seront traités.");
			log.debug(" On a au total : " + selectedSites.size() + " sites c-a-d  " + String.join(", ", selectedSites) );
			siteIds =  String.join(", ", selectedSites);			
		}
		deleteAssignmentSubmissions(siteIds);
		deleteQuizSubmissions(siteIds);
		
		log.debug("Fin du traitement");
		isRunning = false;

	}
	
	private List<String> getSelectedSites(String subStartDate, String subEndDate) {
		List<String> siteIds = new ArrayList<String>();
		String selectedSites = "select distinct SUBSTR(sr.REALM_ID,7) siteId from sakai_realm_provider sp, SAKAI_REALM sr"
				+ " where sr.REALM_KEY = sp.REALM_KEY and  sp.PROVIDER_ID NOT in (select ENTERPRISE_ID from CM_MEMBER_CONTAINER_T "
				+ "where CATEGORY like 'SC.COMPT.') and sr.REALM_ID not like '%/group/%' "
				+ "and sr.CREATEDON >= TO_DATE('" + subStartDate + "' ,'YYYY/MM/DD') and sr.CREATEDON <= TO_DATE('"
				+ subEndDate + "' ,'YYYY/MM/DD')";

		siteIds = sqlService.dbRead(selectedSites, null, new SqlReader<String>() {
			@Override
			public String readSqlResultRecord(ResultSet result) throws SqlReaderFinishedException {
				try {
					return "'" + result.getString("siteId") + "'";
				} catch (SQLException e) {
					e.printStackTrace();
					return null;
				}
			}
		});
		return siteIds;
	}

	private void deleteAssignmentSubmissions(String siteIds) {
		String selectedAssignments = "select ASSIGNMENT_ID from ASN_ASSIGNMENT " + "where CONTEXT in (" + siteIds + ")";

		String affectedContent = "select substr(ATTACHMENT, 9)  from ASN_SUBMISSION_ATTACHMENTS where SUBMISSION_ID in "
		+ "(select SUBMISSION_ID from ASN_SUBMISSION where ASSIGNMENT_ID in (" + selectedAssignments + "))";

		String affectedContentFeedback = "select substr(FEEDBACK_ATTACHMENT, 9)  from ASN_SUBMISSION_FEEDBACK_ATTACH where SUBMISSION_ID in "
				+ "(select SUBMISSION_ID from ASN_SUBMISSION where ASSIGNMENT_ID in (" + selectedAssignments + "))";

		String affectedSubmissions = "select SUBMISSION_ID from ASN_SUBMISSION " + " where ASSIGNMENT_ID in ( "
				+ selectedAssignments + ")";

		List<String> assignments = sqlService.dbRead(selectedAssignments, null, new SqlReader<String>() {

			@Override
			public String readSqlResultRecord(ResultSet result) throws SqlReaderFinishedException {
				try {
					return result.getString("ASSIGNMENT_ID");
				} catch (SQLException e) {
					e.printStackTrace();
					return null;
				}
			}
		});

		log.debug(" Remises de travaux affectés: " + assignments.size());

		// Delete data from contentreview
		boolean deleteContentReview = sqlService.dbWrite("delete from CONTENTREVIEW_ITEM where taskid in "
				+ "(select '/assignment/a/' || context || '/' || ASSIGNMENT_ID from ASN_ASSIGNMENT "
				+ "where SITEID in (" + siteIds + "))");

		if (deleteContentReview) {
			log.debug("Les rapports de détection de similitudes ont été effacé.");
		}

		// Delete resources submissions
		boolean deletedContentResourceBodyBinary = sqlService
				.dbWrite("delete from CONTENT_RESOURCE_BODY_BINARY where resource_id in ( " + affectedContent + ")");

		boolean deletedContentResource = sqlService
				.dbWrite("delete from CONTENT_RESOURCE where resource_id in ( " + affectedContent + ")");

		if (deletedContentResourceBodyBinary && deletedContentResource) {
			log.debug("Les ressources de content_resource ont été effacé.");
		}

		// Delete resources submissions feedback
		boolean deletedContentResourceBodyBinaryFeedback = sqlService.dbWrite(
				"delete from CONTENT_RESOURCE_BODY_BINARY where resource_id in ( " + affectedContentFeedback + ")");

		boolean deletedContentResourceFeedback = sqlService
				.dbWrite("delete from CONTENT_RESOURCE where resource_id in ( " + affectedContentFeedback + ")");

		if (deletedContentResourceBodyBinaryFeedback && deletedContentResourceFeedback) {
			log.debug("Les ressources de rétroaction de content_resources ont été effacé.");
		}

		// Delete submissions properties
		boolean deleteSubmissionProperties = sqlService
				.dbWrite("delete from ASN_SUBMISSION_PROPERTIES where SUBMISSION_ID in (" + affectedSubmissions + ")");

		if (deleteSubmissionProperties) {
			log.debug("Les propriétés des soumissions ont été effacé.");
		}

		// Delete submissions feedback attachments
		boolean deleteSubFeedbackAtt = sqlService.dbWrite(
				"delete from ASN_SUBMISSION_FEEDBACK_ATTACH where SUBMISSION_ID in (" + affectedSubmissions + ")");

		if (deleteSubFeedbackAtt) {
			log.debug("Les fichiers attachés de rétroaction ont été effacé.");
		}

		// Delete submissions attachments
		boolean deleteSubAtt = sqlService
				.dbWrite("delete from ASN_SUBMISSION_ATTACHMENTS where SUBMISSION_ID in (" + affectedSubmissions + ")");

		if (deleteSubAtt) {
			log.debug("Les fichiers attachés ont été effacé.");
		}

		// Delete submissions submitters
		boolean deleteSubSubmitters = sqlService.dbWrite(
				"delete from ASN_SUBMISSION_SUBMITTER where SUBMISSION_ID in (select SUBMISSION_ID from ASN_SUBMISSION where ASSIGNMENT_ID in (select ASSIGNMENT_ID from ASN_ASSIGNMENT "
						+ "where CONTEXT in (" + siteIds + ")))");

		if (deleteSubSubmitters) {
			log.debug("Les soumissionneurs ont été effacé.");
		}

		// Delete submissions
		boolean deleteSubmissions = sqlService
				.dbWrite("delete from ASN_SUBMISSION where ASSIGNMENT_ID in (select ASSIGNMENT_ID from ASN_ASSIGNMENT "
						+ "where CONTEXT in (" + siteIds + "))");

		if (deleteSubmissions) {
			log.debug("Les soumissions ont été effacé.");
		}

	}

	private void deleteQuizSubmissions(String siteIds) {
		String selectedQuiz = "select distinct QUALIFIERID from SAM_AUTHZDATA_T where FUNCTIONID like 'OWN_PUBLISHED_ASSESSMENT' and AGENTID in ("
				+ siteIds + " )";

		String selectedAssGrading = "select ASSESSMENTGRADINGID from SAM_ASSESSMENTGRADING_T where PUBLISHEDASSESSMENTID in ("
				+ selectedQuiz + ")";

		String selectedItemGrading = "select PUBLISHEDITEMID from SAM_ITEMGRADING_T where ASSESSMENTGRADINGID in ("
				+ selectedAssGrading + ")";

		String selectedItemAttachment = "select RESOURCEID from SAM_ATTACHMENT_T where ITEMID in ("
				+ selectedItemGrading + ")";

		List<String> quiz = sqlService.dbRead(selectedQuiz, null, new SqlReader<String>() {

			@Override
			public String readSqlResultRecord(ResultSet result) throws SqlReaderFinishedException {
				try {
					return result.getString("QUALIFIERID");
				} catch (SQLException e) {
					e.printStackTrace();
					return null;
				}
			}
		});
		log.debug("Quiz affectés: " + quiz.size());

		// Delete submitted files
		boolean deleteContent = sqlService.dbWrite(
				"delete from CONTENT_RESOURCE_BODY_BINARY where RESOURCE_ID in" + "(" + selectedItemAttachment + ")");

		boolean deleteContentBB = sqlService
				.dbWrite("delete from CONTENT_RESOURCE where RESOURCE_ID in" + "(" + selectedItemAttachment + ")");

		boolean deleteSubAttachment = sqlService
				.dbWrite("delete from SAM_ATTACHMENT_T where ITEMID in (" + selectedItemGrading + ")");

		if (deleteContent && deleteContentBB && deleteSubAttachment) {
			log.debug("Les ressources ont été effacé.");
		}

		// Delete submission items
		boolean deleteItems = sqlService
				.dbWrite("delete from SAM_ITEMGRADING_T where ASSESSMENTGRADINGID in (" + selectedAssGrading + ")");
		if (deleteItems) {
			log.debug("Les items de réponses ont été effacé.");
		}

		// Delete submissions
		boolean deleteSubmissions = sqlService.dbWrite("select ASSESSMENTGRADINGID from SAM_ASSESSMENTGRADING_T"
				+ " where PUBLISHEDASSESSMENTID in (" + selectedQuiz + ")");
		if (deleteSubmissions) {
			log.debug("Les réponses des étudiants aux quiz ont été effacé.");
		}
	}

	private Date getDate(String date) {
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
		Date convertedDate = null;
		try {
			convertedDate = dateFormat.parse(date);
		} catch (ParseException e) {
			log.debug("Unparseable date: " + date);
		}
		return convertedDate;

	}

}
