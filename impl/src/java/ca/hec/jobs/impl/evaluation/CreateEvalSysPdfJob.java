package ca.hec.jobs.impl.evaluation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.PropertyResourceBundle;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.content.api.ContentCollection;
import org.sakaiproject.content.api.ContentCollectionEdit;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.content.api.ContentResourceEdit;
import org.sakaiproject.coursemanagement.api.AcademicCareer;
import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.coursemanagement.api.Section;
import org.sakaiproject.coursemanagement.api.exception.IdNotFoundException;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.evaluation.logic.EvalEvaluationService;
import org.sakaiproject.evaluation.logic.model.EvalGroup;
import org.sakaiproject.evaluation.model.EvalEvaluation;
import org.sakaiproject.exception.IdInvalidException;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.IdUsedException;
import org.sakaiproject.exception.InconsistentException;
import org.sakaiproject.exception.OverQuotaException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.exception.ServerOverloadException;
import org.sakaiproject.exception.TypeException;

import ca.hec.jobs.impl.AbstractQuartzJobImpl;

public class CreateEvalSysPdfJob extends AbstractQuartzJobImpl {

    public final static String CONFIG_FOLDER = "/content/group/opensyllabusAdmin/config/";

    public static final String EVALSYS_TERMS_FILE = "evalsysTerm.properties";

    public static final String BUNDLE_KEY = "term_id";
    
    public static final String REPORTS_SITE = "evalsys.reports.site";
    
    public static final String DEPARTMENT_FOLDER_NAME = "departement";
    
    public static final String PROG_FORLDER_NAME = "programmes"; 

    private String termEid;
    
    private PropertyResourceBundle bundle = null;

    private int displayNumber = 0;

    private List<EvalEvaluation> selectedEval = null;

    String evalsysReportsFolder = null;

    private static Log log = LogFactory.getLog(CreateEvalSysPdfJob.class);

    protected ContentHostingService contentHostingService = null;

    public void setContentHostingService(ContentHostingService service) {
	contentHostingService = service;
    }

    EvalEvaluationService evaluationService;

    public void setEvaluationService(EvalEvaluationService evaluationService) {
	this.evaluationService = evaluationService;
    }

    private EntityManager entityManager;

    public void setEntityManager(EntityManager entityManager) {
	this.entityManager = entityManager;
    }

    @Override
    public void execute(JobExecutionContext context)
	    throws JobExecutionException {

	// Get folder where reports will be saved
	evalsysReportsFolder =
		"/group/" + ServerConfigurationService.getString(REPORTS_SITE)
			+ "/";
	String reportPdfId = null;
	ResourcePropertiesEdit resourceProperties = null;

	EvalEvaluation eval = null;
	Map<Long, List<EvalGroup>> evalGroups;
	List<EvalGroup> evalGs;
	Long[] evalIds;
	String[] groupIds;
	ContentResourceEdit resourceEdit = null;
	ByteArrayOutputStream byteOutputStream;
	String departmentFolderName = null;
	String departmentFolderId = null;
	ContentCollection departmentFolderCollection = null;

	String progFolderName = null;
	String progFolderId = null;
	ContentCollection progFolderCollection = null;
	String fileTitle = null;

	int fileCount = 0;

	loginToSakai();

	try {

	    // Check if property file exists and retrieve it
	    Reference reference =
		    entityManager
			    .newReference(CONFIG_FOLDER + EVALSYS_TERMS_FILE);
	    ContentResource resource =
		    contentHostingService.getResource(reference.getId());
	    bundle = getResouceBundle(resource);
	    // TODO: get a list from the bundle
	    termEid = bundle.getString(BUNDLE_KEY);

	    selectedEval = evaluationService.getEvaluationsByTermId(termEid);
	    evalIds = new Long[selectedEval.size()];
	    for (int i = 0; i < selectedEval.size(); i++) {
		eval = selectedEval.get(i);
		evalIds = new Long[1];
		evalIds[0] = new Long(eval.getId());

		// Retrieve group
		evalGroups =
			evaluationService.getEvalGroupsForEval(evalIds, true,
				null);
		evalGs = evalGroups.get(eval.getId());
		groupIds = new String[1];
		for (int j = 0; j < evalGs.size(); j++) {
		    groupIds[0] = ((EvalGroup) evalGs.get(j)).evalGroupId;

		    //Create PDF
		    byteOutputStream = new ByteArrayOutputStream();
		    evaluationService.exportReport(eval,   groupIds[0], byteOutputStream,  EvalEvaluationService.PDF_RESULTS_REPORT);
//		    byteOutputStream =
//			    (ByteArrayOutputStream) buildPDFReport(eval,
//				    ((EvalGroup) evalGs.get(j)), lang);

			String realmId = groupIds[0];
			if (realmId.contains("/section")) {
				realmId = realmId.substring(0, realmId.indexOf("/section"));
			}

		    // Get the department and create folder
			try
			{
				departmentFolderName = getDepartment(realmId);
			}
			catch(IdNotFoundException e) 
			{
				log.info("Cannot find department for realmId" + realmId + ". Skipping to next eval group.");
				
				continue;
			}
			
		    departmentFolderName = removeAccents(departmentFolderName);
		    departmentFolderId =
			    evalsysReportsFolder + DEPARTMENT_FOLDER_NAME + "/"
				    + departmentFolderName + "/";
		    departmentFolderCollection =
			    createOrGetContentCollection(departmentFolderId,
				    departmentFolderName);

		    // Get le programme and create folder
		    progFolderName = getProgramme(realmId);
		    progFolderId =
			    evalsysReportsFolder + PROG_FORLDER_NAME + "/"
				    + progFolderName + "/";
		    progFolderCollection =
			    createOrGetContentCollection(progFolderId,
				    progFolderName);

		    fileTitle = ((EvalGroup) evalGs.get(j)).title;

		    // add name to file
		    resourceProperties =
			    contentHostingService.newResourceProperties();
		    resourceProperties.addProperty(
			    ResourceProperties.PROP_DISPLAY_NAME, fileTitle
				    + ".pdf");

		    // Save pdf to department folder
		    reportPdfId =
			    departmentFolderCollection.getId() + fileTitle
				    + ".pdf";

		    // Check if file already exists
		    if (resourceExists(reportPdfId)) {
			fileCount += 1;
			 resourceProperties.addProperty(
				    ResourceProperties.PROP_DISPLAY_NAME, fileTitle
				    + "_" + fileCount + ".pdf");
			reportPdfId =
				departmentFolderCollection.getId() + fileTitle
					+ "_" + fileCount + ".pdf";
		    }
		    resourceEdit =
			    (ContentResourceEdit) contentHostingService
				    .addResource(
					    reportPdfId,
					    "application/pdf",
					    new ByteArrayInputStream(
						    byteOutputStream
							    .toByteArray()),
					    resourceProperties, 0);
		    // contentHostingService.commitResource(resourceEdit);

		    // Save pdf to programme folder
		    reportPdfId =
			    progFolderCollection.getId() + fileTitle + ".pdf";

		    // Check if file already exists
		    if (resourceExists(reportPdfId)) {
			reportPdfId =
				progFolderCollection.getId() + fileTitle + "_"
					+ fileCount + ".pdf";
		    }
		    contentHostingService.addResource(reportPdfId,
			    "application/pdf", new ByteArrayInputStream(
				    byteOutputStream.toByteArray()),
			    resourceProperties, 0);

		}

	    }

	} catch (PermissionException e) {
	    e.printStackTrace();
	} catch (IdUnusedException e) {
	    e.printStackTrace();
	} catch (TypeException e) {
	    e.printStackTrace();
	} catch (IdUsedException e) {
	    e.printStackTrace();
	} catch (IdInvalidException e) {
	    e.printStackTrace();
	} catch (InconsistentException e) {
	    e.printStackTrace();
	} catch (OverQuotaException e) {
	    e.printStackTrace();
	} catch (ServerOverloadException e) {
	    e.printStackTrace();
	} catch (Exception e) {
	    e.printStackTrace();
	}

	logoutFromSakai();
    }

    private boolean resourceExists(String resourceId) {
	try {
	    contentHostingService.getResource(resourceId);
	    return true;
	} catch (Exception e) {
	    return false;
	}
    }

    private String removeAccents(String name) {
    	if (name == null)
    		return null;
	String cleanName = "";
	String chars = "àâäéèêëîïôöùûüç";
	String replace = "aaaeeeeiioouuuc";
	int position = -2;

	for (char letter : name.toCharArray()) {
	    position = chars.indexOf(letter);
	    if (position > -1)
		cleanName = cleanName.concat(replace.charAt(position) + "");
	    else
		cleanName = cleanName.concat(letter + "");
	}

	return cleanName;
    }

    // TODO: Une fois que les plans de cours seront section aware il faudra
    // modifier
    // ce code pour gérer toute la liste de provider ids
    /**
     * Get the department from the providerId associated to the Realm
     * 
     * @param realmId
     * @return
     */
    private String getDepartment(String realmId) {
	String department = null;
	String category = null;
	Set<String> providerIds;
	Section section = null;

	providerIds = authzGroupService.getProviderIds(realmId);
	for (String providerId : providerIds) {
		
	    section = cmService.getSection(providerId);
	    category = section.getCategory();
	    department = cmService.getSectionCategoryDescription(category);
	}
	return department;

    }

    /**
     * Get program from course management associated to providerId in Realm
     * 
     * @param realmId
     * @return
     */
    private String getProgramme(String realmId) {
	String programme = null;
	Set<String> providerIds;
	CourseOffering courseOff = null;
	Section section = null;
	AcademicCareer acadCareer = null;

	providerIds = authzGroupService.getProviderIds(realmId);
	for (String providerId : providerIds) {
	    section = cmService.getSection(providerId);
	    courseOff =
		    cmService.getCourseOffering(section.getCourseOfferingEid());
	    acadCareer =
		    cmService.getAcademicCareer(courseOff.getAcademicCareer());
	    programme = acadCareer.getDescription_fr_ca();
	}
	return programme;

    }

    private ContentCollection createOrGetContentCollection(
	    String departmentFolderId, String departmentFolderName)
	    throws Exception {
	ContentCollection departmentFolderCollection = null;
	ResourcePropertiesEdit resourceProperties = null;

	try {
	    departmentFolderCollection =
		    contentHostingService.getCollection(departmentFolderId);
	} catch (IdUnusedException e) {
	    departmentFolderCollection =
		    contentHostingService.addCollection(departmentFolderId);
	    resourceProperties =
		    (ResourcePropertiesEdit) departmentFolderCollection
			    .getProperties();
	    resourceProperties.addProperty(
		    ResourceProperties.PROP_DISPLAY_NAME, departmentFolderName);

	    contentHostingService
		    .commitCollection((ContentCollectionEdit) departmentFolderCollection);
	} catch (TypeException e) {
	    e.printStackTrace();
	} catch (PermissionException e) {
	    e.printStackTrace();
	}

	return departmentFolderCollection;
    }

    /**
     * Logs in the sakai environment
     */
    protected void loginToSakai() {
	super.loginToSakai("CreateEvalSysPdfJob");
    }

 
}
