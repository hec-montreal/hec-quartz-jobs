package ca.hec.jobs.impl.site;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

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
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.UserDirectoryService;

import ca.hec.api.SiteIdFormatHelper;
import ca.hec.jobs.api.site.RemovePastSubmissions;
import lombok.Setter;

public class RemovePastSubmissionsImpl implements RemovePastSubmissions {

	@Setter
	private EmailService emailService;
	@Setter
	private SqlService sqlService;
	@Setter
	protected SessionManager sessionManager;
	@Setter
	protected ServerConfigurationService serverConfigService;

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
		Date subFromStartDate = getDate(subFromStartDateString);
		Date subFromEndDate = getDate(subFromEndDateString);

		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
		Date convertedDate = null;
		try {
			subFromStartDate = dateFormat.parse(subFromStartDateString);
			subFromEndDate = dateFormat.parse(subFromEndDateString);
		} catch (ParseException e) {
			log.debug("Les formats des dates ne sont pas corrects");
		}
		
		log.info(" Les travaux et quiz entre " + subFromStartDateString + " et " +  subFromEndDateString + " seront traités.");
		
		deleteAssignmentSubmissions(subFromStartDateString, subFromEndDateString);
		deleteQuizSubmissions(subFromStartDateString, subFromEndDateString);
		
		isRunning = false;

	}

	private void deleteAssignmentSubmissions(String subStartDate, String subEndDate) {
		String selectedAssignments = "select ASSIGNMENT_ID from ASN_ASSIGNMENT " + "where OPEN_DATE >= TO_DATE('"
				+ subStartDate + "' ,'YYYY/MM/DD')" + "and CLOSE_DATE <= TO_DATE('" + subEndDate + "' ,'YYYY/MM/DD')";

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

		log.info(" Remises de travaux affectés: " + assignments.size());

		// Delete data from contentreview
		boolean deleteContentReview = sqlService.dbWrite("delete from CONTENTREVIEW_ITEM where taskid in "
				+ "(select '/assignment/a/' || context || '/' || ASSIGNMENT_ID from ASN_ASSIGNMENT "
				+ "where OPEN_DATE >= TO_DATE('" + subStartDate + "' ,'YYYY/MM/DD') " + "and CLOSE_DATE <= TO_DATE('"
				+ subEndDate + "' ,'YYYY/MM/DD'))");

		if (deleteContentReview) {
			log.info("Les rapports de détection de similitudes ont été effacé.");
		}

		// Delete resources submissions
		String affectedContent = "select substr(ATTACHMENT, 9)  from ASN_SUBMISSION_ATTACHMENTS where SUBMISSION_ID in "
				+ "(select SUBMISSION_ID from ASN_SUBMISSION where ASSIGNMENT_ID in (" + selectedAssignments + "))";

		boolean deletedContentResourceBodyBinary = sqlService
				.dbWrite("delete from CONTENT_RESOURCE_BODY_BINARY where resource_id in ( " + affectedContent + ")");

		boolean deletedContentResource = sqlService
				.dbWrite("delete from CONTENT_RESOURCE where resource_id in ( " + affectedContent + ")");

		if (deletedContentResourceBodyBinary && deletedContentResource) {
			log.info("Les ressources de content_resource ont été effacé.");
		}

		// Delete resources submissions feedback
		String affectedContentFeedback = "select substr(ATTACHMENT, 9)  from ASN_SUBMISSION_FEEDBACK_ATTACH where SUBMISSION_ID in "
				+ "(select SUBMISSION_ID from ASN_SUBMISSION where ASSIGNMENT_ID in (" + selectedAssignments + "))";

		boolean deletedContentResourceBodyBinaryFeddback = sqlService
				.dbWrite("delete from CONTENT_RESOURCE_BODY_BINARY where resource_id in ( " + affectedContent + ")");

		boolean deletedContentResourceFeedback = sqlService
				.dbWrite("delete from CONTENT_RESOURCE where resource_id in ( " + affectedContent + ")");

		if (deletedContentResourceBodyBinaryFeddback && deletedContentResourceFeedback) {
			log.info("Les ressources de rétroaction de content_resources ont été effacé.");
		}

		// Delete submissions properties
		boolean deleteSubmissionProperties = sqlService.dbWrite(
				"delete from ASN_SUBMISSION_PROPERTIES where SUBMISSION_ID in (select SUBMISSION_ID from ASN_SUBMISSION where ASSIGNMENT_ID in (select ASSIGNMENT_ID from ASN_ASSIGNMENT "
						+ "where OPEN_DATE >= TO_DATE('" + subStartDate + "' ,'YYYY/MM/DD') "
						+ "and CLOSE_DATE <= TO_DATE('" + subEndDate + "' ,'YYYY/MM/DD')))");

		if (deleteSubmissionProperties) {
			log.info("Les propriétés des soumissions ont été effacé.");
		}

		// Delete submissions feedback attachments
		boolean deleteSubFeedbackAtt = sqlService.dbWrite(
				"delete from ASN_SUBMISSION_FEEDBACK_ATTACH where SUBMISSION_ID in (select SUBMISSION_ID from ASN_SUBMISSION where ASSIGNMENT_ID in (select ASSIGNMENT_ID from ASN_ASSIGNMENT "
						+ "where OPEN_DATE >= TO_DATE('" + subStartDate + "' ,'YYYY/MM/DD') "
						+ "and CLOSE_DATE <= TO_DATE('" + subEndDate + "' ,'YYYY/MM/DD')))");

		if (deleteSubFeedbackAtt) {
			log.info("Les fichiers attachés de rétroaction ont été effacé.");
		}

		// Delete submissions attachments
		boolean deleteSubAtt = sqlService.dbWrite(
				"delete from ASN_SUBMISSION_ATTACHMENTS where SUBMISSION_ID in (select SUBMISSION_ID from ASN_SUBMISSION where ASSIGNMENT_ID in (select ASSIGNMENT_ID from ASN_ASSIGNMENT "
						+ "where OPEN_DATE >= TO_DATE('" + subStartDate + "' ,'YYYY/MM/DD') "
						+ "and CLOSE_DATE <= TO_DATE('" + subEndDate + "' ,'YYYY/MM/DD')))");

		if (deleteSubAtt) {
			log.info("Les fichiers attachés ont été effacé.");
		}

		// Delete submissions submitters
		boolean deleteSubSubmitters = sqlService.dbWrite(
				"delete from ASN_SUBMISSION_SUBMITTER where SUBMISSION_ID in (select SUBMISSION_ID from ASN_SUBMISSION where ASSIGNMENT_ID in (select ASSIGNMENT_ID from ASN_ASSIGNMENT "
						+ "where OPEN_DATE >= TO_DATE('" + subStartDate + "' ,'YYYY/MM/DD') "
						+ "and CLOSE_DATE <= TO_DATE('" + subEndDate + "' ,'YYYY/MM/DD')))");

		if (deleteSubSubmitters) {
			log.info("Les soumissionneurs ont été effacé.");
		}

		// Delete submissions
		boolean deleteSubmissions = sqlService
				.dbWrite("delete from ASN_SUBMISSION where ASSIGNMENT_ID in (select ASSIGNMENT_ID from ASN_ASSIGNMENT "
						+ "where OPEN_DATE >= TO_DATE('" + subStartDate + "' ,'YYYY/MM/DD') "
						+ "and CLOSE_DATE <= TO_DATE('" + subEndDate + "' ,'YYYY/MM/DD'))");

		if (deleteSubmissions) {
			log.info("Les soumissions ont été effacé.");
		}

	}

	private void deleteQuizSubmissions(String subStartDate, String subEndDate) {
		String selectedQuiz = "select ASSESSMENTID from SAM_PUBLISHEDACCESSCONTROL_T where " + "STARTDATE >= TO_DATE('"
				+ subStartDate + "' ,'YYYY/MM/DD') " + "and DUEDATE <= TO_DATE('" + subEndDate + "' ,'YYYY/MM/DD')";

		String selectedAssGrading = "select ASSESSMENTGRADINGID from SAM_ASSESSMENTGRADING_T where PUBLISHEDASSESSMENTID in ("
				+ selectedQuiz + ")";

		String selectedItemGrading = "select PUBLISHEDITEMID from SAM_ITEMGRADING_T where ASSESSMENTGRADINGID in ("
				+ selectedAssGrading + ")";

		String selectedItemAttachment = "select RESOURCE_ID from SAM_ATTACHMENT_T where ITEMID in ("
				+ selectedItemGrading + ")";
		
		List<String> quiz = sqlService.dbRead(selectedQuiz, null, new SqlReader<String>() {

			@Override
			public String readSqlResultRecord(ResultSet result) throws SqlReaderFinishedException {
				try {
					return result.getString("ASSESSMENTID");
				} catch (SQLException e) {
					e.printStackTrace();
					return null;
				}
			}
		});

		log.info("Quiz affectés: " + quiz.size());


		// Delete submitted files
		boolean deleteContent = sqlService.dbWrite(
				"delete from CONTENT_RESOURCE_BODY_BINARY where RESOURCE_ID in" + "(" + selectedItemAttachment + ")");

		boolean deleteContentBB = sqlService
				.dbWrite("delete from CONTENT_RESOURCE where RESOURCE_ID in" + "(" + selectedItemAttachment + ")");

		boolean deleteSubAttachment = sqlService
				.dbWrite("delete from SAM_ATTACHMENT_T where ITEMID in (" + selectedItemGrading + ")");

		if (deleteContent && deleteContentBB && deleteSubAttachment) {
			log.info("Les ressources ont été effacé.");
		}

		// Delete submission items
		boolean deleteItems = sqlService
				.dbWrite("delete from SAM_ITEMGRADING_T where ASSESSMENTGRADINGID in (" + selectedAssGrading + ")");
		if (deleteItems) {
			log.info("Les items de réponses ont été effacé.");
		}

		// Delete submissions
		boolean deleteSubmissions = sqlService.dbWrite("select ASSESSMENTGRADINGID from SAM_ASSESSMENTGRADING_T"
				+ " where PUBLISHEDASSESSMENTID in (" + selectedQuiz + ")");
		if (deleteSubmissions) {
			log.info("Les réponses des étudiants aux quiz ont été effacé.");
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
