package ca.hec.jobs.impl.testtravauxarchive;

import ca.hec.jobs.impl.AbstractQuartzJobImpl;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.assignment.api.model.Assignment;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;

import org.sakaiproject.exception.*;

import org.sakaiproject.site.api.Group;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.assignment.api.AssignmentService;

import org.sakaiproject.tool.assessment.facade.PublishedAssessmentFacade;
import org.sakaiproject.tool.assessment.facade.PublishedAssessmentFacadeQueries;
import org.sakaiproject.tool.assessment.services.PersistenceService;

import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.content.api.ContentCollection;
import org.sakaiproject.content.api.ContentCollectionEdit;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.user.api.UserNotDefinedException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;



public class HecTestTravauxPdfJob extends AbstractQuartzJobImpl {

    private static Log log = LogFactory.getLog(HecTestTravauxPdfJob.class);
    private static boolean isRunning = false;



    @Setter
    protected SiteService siteService;

    @Setter
    protected AssignmentService assignmentService;

    @Setter
    protected UserDirectoryService userDirectoryService;

    @Setter
    protected ContentHostingService contentHostingService;

    @Setter
    private PersistenceService persistenceService;

    private ExportService exportService = new ExportService();

    //
    //private final String REPORTS_SITE = "archivesintrasfinaux";

    String archivesReportsFolder = null;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {

        if (isRunning) {
            log.warn("Stopping job since it's already running");
            return;
        }
        isRunning = true;

        loginToSakai("ArchivesIntraFinalJob");// it's needed to be able to have the assignments

        String REPORTS_SITE = serverConfigService.getString("hec.quartzjob.testtravaux.reportssite", "archivesintrasfinaux");

        List<String> terms = null;

        String term = context.getMergedJobDataMap().getString("term");

        if (!term.isEmpty()) {
            terms = Arrays.asList(term.split(","));
        }

        else {
            log.error("Must specify either siteId(s) or session.");
            return;
        }

        try {
            // ####################for debug
            for (int i = 0; i < terms.size(); i++) {
                List<Site> sites = null;



                String siteId = null;

                Collection<Assignment> assignments = null;
                //List<PublishedAssessmentFacade> assessments = null;
                List<PublishedAssessmentFacade> assessments = null;



                ByteArrayOutputStream otherSitesByteOutputStream;

                ResourcePropertiesEdit otherSitesResourceProperties = null;

                String otherSitesReportTextId = null;

                ContentCollection sessionFolderCollection = null;
                Map<String, String> criteria = new HashMap<String, String>();
                //##########################for debug
                criteria.put("term", terms.get(i));
                String actualTerm = terms.get(i);

//                criteria.put("term", "H2023");
//                String actualTerm = "H2023";

                //log.info("/////////TERM: ######## "+ terms.get(i));

                String sessionFolderId = "";
                String sessionFolderName = "";
                String pattern = "yyyyMMddHHmm";
                ArrayList<String> sitesWithoutInstrasFinals = new ArrayList<String>();
                DateFormat df = new SimpleDateFormat(pattern);
                // Get the today date using Calendar object.
                Date today = Calendar.getInstance().getTime();
                String todayAsString = df.format(today);

                archivesReportsFolder =
                        "/group/" + REPORTS_SITE
                                + "/";


                sessionFolderName = actualTerm + "_" + todayAsString;

                sessionFolderId =
                        archivesReportsFolder + sessionFolderName +"/";

                sessionFolderCollection =
                        createOrGetContentCollection(sessionFolderId, sessionFolderName);


                sites = siteService.getSites(SiteService.SelectionType.ANY, "course", null, criteria, SiteService.SortType.NONE, null);
//###########################for debug
                for (Site site : sites) {

                    //###########################for debug
                    //siteId = "ATEL49037.H2023";
                    siteId = site.getId();

                    //Site site = siteService.getSite(siteId);



                    ContentCollection siteFolderCollection = null;
                    String siteFolderId = "";
                    String siteFolderName = "";




                    siteFolderName = siteId;
                    Boolean hasIntrasFinals = false;

                    siteFolderId =
                            sessionFolderCollection.getId() + siteId +"/";

                    assignments = assignmentService.getAssignmentsForContext(siteId);

                    assessments =
                    persistenceService.getPublishedAssessmentFacadeQueries().getBasicInfoOfAllPublishedAssessments2(
                            PublishedAssessmentFacadeQueries.TITLE, true, siteId);





                     //assessments = publishedAssessmentService.getAllActivePublishedAssessments("startDate");
                    //assessments = publishedAssessmentService.getBasicInfoOfAllPublishedAssessments2("startDate", true, siteId);



                    if(!assignments.isEmpty()){



                        hasIntrasFinals = createAssignmentsFiles(assignments, hasIntrasFinals, site, siteId, siteFolderId, siteFolderName, siteFolderCollection);

                    }
                    if(!assessments.isEmpty()) {
                        hasIntrasFinals = createAssessmentFiles(assessments, hasIntrasFinals, site, siteId, siteFolderId, siteFolderName, siteFolderCollection);

                    }

// methode assignment et test quiz



                    if(hasIntrasFinals.equals(false)) {
                        sitesWithoutInstrasFinals.add(siteId);
                    }
//###########################for debug
                }
                String otherSitesTextToDisplay = sitesWithoutInstrasFinals.toString();
                // add name to file
                otherSitesResourceProperties =
                        contentHostingService.newResourceProperties();
                otherSitesResourceProperties.addProperty(
                        ResourceProperties.PROP_DISPLAY_NAME, "sitesSansInstrasFinals"
                                + ".txt");
                // Create text file
                otherSitesByteOutputStream = new ByteArrayOutputStream();
                byte[] otherSitesArray = otherSitesTextToDisplay.getBytes();

                // Writes data to the output stream
                otherSitesByteOutputStream.write(otherSitesArray);

                // Save text to  folder
                otherSitesReportTextId = sessionFolderCollection.getId() +
                        "sitesSansInstrasFinals"
                        + ".txt";

                contentHostingService.addResource(otherSitesReportTextId,
                        "text/plain", new ByteArrayInputStream(
                                otherSitesByteOutputStream.toByteArray()),
                        otherSitesResourceProperties, 0);
           }

            log.info("The terms " + terms.toString() + " have been treated.");
            logoutFromSakai();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            isRunning = false;
        }
    }
    private Boolean createAssessmentFiles(List<PublishedAssessmentFacade> assessments, Boolean hasIntrasFinals,
                                           Site site, String siteId, String  siteFolderId, String siteFolderName, ContentCollection siteFolderCollection) {
        for (PublishedAssessmentFacade assessment : assessments) {

            OutputStream assessmentOutputStream = null;

            log.info(Long.toString(assessment.getAssessmentId()));

             exportService.extractAssessment(Long.toString(assessment.getAssessmentId()), assessmentOutputStream);


        }
        return hasIntrasFinals;
    }
private Boolean createAssignmentsFiles(Collection<Assignment> assignments, Boolean hasIntrasFinals,
                                      Site site, String siteId, String  siteFolderId, String siteFolderName, ContentCollection siteFolderCollection){
    for (Assignment assignment : assignments) {

        String assignmentFolderId = "";
        String assignmentFolderName = "";
        String fileTitle = null;
        String reportTextId = null;
        ByteArrayOutputStream byteOutputStream;
        ResourcePropertiesEdit resourceProperties = null;


        try {
            if(!assignment.getDraft() && (assignment.getTitle().toUpperCase().contains("INTRA") || assignment.getTitle().toUpperCase().contains("FINAL")
                    || assignment.getTitle().toUpperCase().contains("MIDTERM"))){

                ContentCollection assignmentFolderCollection = null;


                hasIntrasFinals = true;
                String userName = "";
                String titleWithoutAccents = "";
                String titleWithoutSpacesAndAccents = "";
                String modeRemise = "";
                String note = "";

                try {
                    userName = userDirectoryService.getUser(assignment.getAuthor()).getDisplayName();
                }catch (UserNotDefinedException e2){
                    e2.printStackTrace();
                    continue;
                }
                titleWithoutAccents = removeAccents(assignment.getTitle());
                titleWithoutSpacesAndAccents = replaceSpace(titleWithoutAccents);
                assignmentFolderName = titleWithoutSpacesAndAccents;
                modeRemise = getModeRemise(assignment);
                String nouvelleRemise = "0";
                String groupsDisplay = "Site";
                note = getNote(assignment);

                if(assignment.getProperties().containsKey("allow_resubmit_number")){

                    nouvelleRemise = assignment.getProperties().get("allow_resubmit_number");

                }
                // get the section
                Set<String> groups = null;

                if(!assignment.getGroups().isEmpty()){

                    groupsDisplay = "";
                    groups = assignment.getGroups();
                    Iterator<String> groupsIterator = groups.iterator();

                    while(groupsIterator.hasNext()) {

                        String groupUrl = groupsIterator.next();
                        groupUrl = groupUrl.substring(groupUrl.lastIndexOf("/") + 1);
                        Group group = site.getGroup(groupUrl);
                        String groupTitle = group.getTitle();

                        groupsDisplay = groupsDisplay + groupTitle + " ";
                    }

                }

                String textToDisplay =
                        "Titre du travail: "+ titleWithoutSpacesAndAccents + "\n" +
                                "Identifiant du cours: "+ siteId + "\n" +
                                "Créé par: " + userName + "\n" +
                                "Date de création: " + assignment.getDateCreated().toString() + "\n" +
                                "Ouvert: " + assignment.getOpenDate().toString() + "\n" +
                                "Date d'échéance du travail: " + assignment.getDueDate().toString() + "\n" +
                                "Date d'échéance finale: " + assignment.getCloseDate().toString() + "\n" +
                                "Modifié par l'instructeur: " + assignment.getDateModified().toString() + "\n" +
                                "Pour: " + groupsDisplay + "\n" +
                                "Mode de remise: " + modeRemise + "\n" +
                                "Nombre de nouvelles remises permises: " + nouvelleRemise + "\n" +
                                "Accepter les soumissions en retard jusqu'à: " + assignment.getDropDeadDate().toString() + "\n" +
                                "Note: " + note + "\n\n" +
                                "Instructions pour le travail - "+ "\n\n" + assignment.getInstructions().replaceAll("<[^>]*>", "")+ "\n\n" ;


                fileTitle = replaceSpace(removeAccents(assignment.getTitle().toLowerCase()));



                siteFolderCollection =
                        createOrGetContentCollection(siteFolderId, siteFolderName);

                assignmentFolderId =
                        siteFolderCollection.getId() + assignmentFolderName + "/";

                assignmentFolderCollection =
                        createOrGetContentCollection(assignmentFolderId, assignmentFolderName);

                // add name to file
                resourceProperties =
                        contentHostingService.newResourceProperties();
                resourceProperties.addProperty(
                        ResourceProperties.PROP_DISPLAY_NAME, fileTitle
                                + ".txt");
                // Create text file
                byteOutputStream = new ByteArrayOutputStream();
                byte[] array = textToDisplay.getBytes();

                // Writes data to the output stream
                byteOutputStream.write(array);

                // Save text to  folder
                reportTextId = assignmentFolderCollection.getId() +
                        fileTitle
                        + ".txt";

                contentHostingService.addResource(reportTextId,
                        "text/plain", new ByteArrayInputStream(
                                byteOutputStream.toByteArray()),
                        resourceProperties, 0);

                if(!assignment.getAttachments().isEmpty()){

                    String finalSiteFolderId = assignmentFolderId;

                    assignment.getAttachments().stream().forEach(attachment ->
                            {
                                try {
                                    String att = attachment.toString();
                                    // get rid of first part of URL: "/content/"
                                    String attFolder = att.substring(8);

                                    contentHostingService.copy(
                                            attFolder,
                                            finalSiteFolderId + att.substring(att.lastIndexOf("/") + 1));

                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }

                    );
                }


            }
        } catch (Exception e1) {
            e1.printStackTrace();
        }
    }
    return hasIntrasFinals;

}
    private String getModeRemise(Assignment assignment){
        if(assignment.getTypeOfSubmission().toString().equals("TEXT_ONLY_ASSIGNMENT_SUBMISSION")){
            return "Saisie dans une zone de texte";
        }else if(assignment.getTypeOfSubmission().toString().equals("ATTACHMENT_ONLY_ASSIGNMENT_SUBMISSION")){
            return "Fichiers joints seulement";
        }else if(assignment.getTypeOfSubmission().toString().equals("TEXT_AND_ATTACHMENT_ASSIGNMENT_SUBMISSION")){
            return "Saisie dans une zone de texte et fichiers joints";
        }else if(assignment.getTypeOfSubmission().toString().equals("NON_ELECTRONIC_ASSIGNMENT_SUBMISSION")){
            return "Remise papier";
        }else if(assignment.getTypeOfSubmission().toString().equals("SINGLE_ATTACHMENT_SUBMISSION")){
            return "Un seul fichier joint";
        }else{
            return "";
        }

    }
    private String getNote(Assignment assignment){
        if(assignment.getTypeOfGrade().toString().equals("UNGRADED_GRADE_TYPE")){
            return "Aucune note";
        }else if(assignment.getTypeOfGrade().toString().equals("LETTER_GRADE_TYPE")){
            return "Notes litterales";
        }else if(assignment.getTypeOfGrade().toString().equals("SCORE_GRADE_TYPE")){
            return "Notes numériques";
        }else if(assignment.getTypeOfGrade().toString().equals("PASS_FAIL_GRADE_TYPE")){
            return "Réussite/échec";
        }else if(assignment.getTypeOfGrade().toString().equals("CHECK_GRADE_TYPE")){
            return "Coche (vérifié)";
        }else{
            return "";
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

    private String replaceSpace(String name){
        if (name == null)
            return null;
        String cleanName = "";
        cleanName = name.replace(" ", "_");
        return cleanName;

    }

    private ContentCollection createOrGetContentCollection(
            String folderId, String folderName)
            throws Exception {
        ContentCollection folderCollection = null;
        ResourcePropertiesEdit resourceProperties = null;

        try {
            folderCollection =
                    contentHostingService.getCollection(folderId);
        } catch (IdUnusedException e) {
            folderCollection =
                    contentHostingService.addCollection(folderId);
            resourceProperties =
                    (ResourcePropertiesEdit) folderCollection
                            .getProperties();
            resourceProperties.addProperty(
                    ResourceProperties.PROP_DISPLAY_NAME, folderName);

            contentHostingService
                    .commitCollection((ContentCollectionEdit) folderCollection);
        } catch (TypeException e) {
            e.printStackTrace();
        } catch (PermissionException e) {
            e.printStackTrace();
        }

        return folderCollection;
    }

}
