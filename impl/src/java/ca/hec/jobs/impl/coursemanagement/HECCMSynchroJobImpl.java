package ca.hec.jobs.impl.coursemanagement;

import ca.hec.api.SiteIdFormatHelper;
import ca.hec.jobs.api.coursemanagement.HECCMSynchroJob;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.AuthzPermissionException;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.*;
import org.sakaiproject.coursemanagement.api.exception.IdNotFoundException;
import org.sakaiproject.email.api.EmailService;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by 11091096 on 2017-04-27.
 */
public class HECCMSynchroJobImpl implements HECCMSynchroJob {
    @Setter
    private EmailService emailService;
    @Setter
    protected CourseManagementAdministration cmAdmin;
    @Setter
    protected CourseManagementService cmService;
    @Setter
    protected SessionManager sessionManager;
    @Setter
    protected SiteIdFormatHelper siteIdFormatHelper;
    @Setter
    protected SiteService siteService;
    @Setter
    protected AuthzGroupService authzGroupService;

    private static Log log = LogFactory.getLog(HECCMSynchroJobImpl.class);

    private static final String NOTIFICATION_EMAIL_PROP = "hec.error.notification.email";
    private static final Integer MIN_NUMBER_OF_LINES = 5;
    private static boolean isRunning = false;

    //Variable for debug mode
    DebugMode debugMode;

    //File reader variables
    private String directory;
    private String delimeter = ";";
    private String[] token;
    private BufferedReader breader = null;
    private String buffer = null;
    Set<String> studentEnrollmentsToDelete, instructorsToDelete, coordinatorsToDelete, coordinatorsToDeleteForCI;
    Set<String> syncedCanonicalCourses, syncedCourseOfferings;
    Date startTime, endTime;
    //Data to work on
    Set<String> selectedSessions, selectedCourses;
    Map<String, InstructionMode> instructionMode;
    Set<String> sectionsToRefresh;
    String error_address = null;

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        if (isRunning) {
            log.error("Job already running!");
            return;
        }

        isRunning = true;

        error_address = ServerConfigurationService.getString(NOTIFICATION_EMAIL_PROP, null);

        String sessionStartDebug = jobExecutionContext.getMergedJobDataMap().getString("cmSessionStart");
        String sessionEndDebug = jobExecutionContext.getMergedJobDataMap().getString("cmSessionEnd");
        String coursesDebug = jobExecutionContext.getMergedJobDataMap().getString("cmCourses");
        String distinctSitesSections = jobExecutionContext.getMergedJobDataMap().getString("distinctSitesSections");

        syncedCanonicalCourses = new HashSet<String>();
        syncedCourseOfferings = new HashSet<String>();

        directory =
                ServerConfigurationService.getString(EXTRACTS_PATH_CONFIG_KEY,
                        null);

        if (directory == null){
            log.error("There is no data folder", new NullPointerException());
        }

        Session session = sessionManager.getCurrentSession();
        try {
            session.setUserEid("admin");
            session.setUserId("admin");
            selectedSessions = new HashSet<String>();
            selectedCourses = new HashSet<String>();
            sectionsToRefresh = new HashSet<String>(); // refresh these authzGroups to ensure role change for C and CI
            startTime = new Date();
            log.info("Starting HEC CM Data Synchro job at " + startTime);

            debugMode = new DebugMode(sessionStartDebug, sessionEndDebug, coursesDebug);

            if (!validateFiles()) {
                log.error("files failed validation");
                return;
            }
            loadInstructionMode();
            loadProgEtudes();
            loadSessions();
            loadServEnseignements();
            loadCourses();
            loadInstructeurs();
            loadEtudiants();
            removeEntriesMarkedToDelete(distinctSitesSections);

            log.info("Refresh authz groups to ensure correct role for coordinators and coordinator-instructors");
            for (String enterpriseId : sectionsToRefresh) {
                refreshGroups(enterpriseId);
            }

            endTime = new Date();

            log.info("Ending HEC CM Data Synchro job at " + endTime + " Synchro lasted " + (endTime.getTime()-startTime.getTime()));

        } finally {
            session.clear();
            isRunning = false;
        }
    }

    private boolean validateFiles() {
        String[] files = new String[]{
            INSTRUCTION_MODE, PROG_ETUD_FILE, SESSION_FILE, SERV_ENS_FILE,
            COURS_FILE, PROF_FILE, ETUDIANT_FILE };

        Calendar currentTime = Calendar.getInstance();
        String error = null;
        boolean ageWarning = false;
        for (String filename : files) {
            File f = new File(directory + File.separator + filename);

            // stop job and send email if file doesn't exist, is too small or older than 24h
            if (!f.isFile()) {
                error = f.getName() + " n'existe pas ou est un répertoire\n";
            }
            else if (!linecountGreaterThan(f.getPath(), MIN_NUMBER_OF_LINES)) {
                error = f.getName() + " contient trop peu de lignes (moins que " + MIN_NUMBER_OF_LINES + ")\n";
            }
            else if ((currentTime.getTimeInMillis() - f.lastModified()) > 1000*60*60*24 ) {
                // only warn for old files, still run the job
                ageWarning = true;
            }

            if (error != null) {
                log.error(error);
                if (error_address != null) {
                    emailService.send("zonecours2@hec.ca", error_address, "La job de synchro a échoué", "Un des fichiers d'extract n'as pas passé la validation\n"+error, null, null, null);
                }
                return false;
            }
        }
        if (ageWarning) {
            emailService.send("zonecours2@hec.ca", error_address, "Fichiers d'extracts trop anciens", "Un ou plus des fichiers d'extracts ont été créé il y a plus de 24h\n", null, null, null);
        }
        return true;
    }

    private boolean linecountGreaterThan(String fileName, Integer lines) {
        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            while (lines > 0) {
                if (reader.readLine() == null) {
                    return false;
                }
                lines--;
            }
        } catch (IOException e) {
            log.error("Error opening file" + fileName + " to count lines");
            e.printStackTrace();
        }
        return true;
    }

    /**
     * Method used to create courses
     */
    private void loadCourses (){
        log.debug("Start loadCourses");

        String courseId, strm, sessionCode, catalogNbr, classSection, courseTitleLong, langue, acadOrg, strmId, acadCareer, classStat;
        String unitsMinimum, typeEvaluation, instructionMode, shortLang;
        String sectionId, enrollmentSetId, courseOfferingId, courseSetId, canonicalCourseId, title, description;
        try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory+ File.separator + COURS_FILE), "ISO-8859-1"));


            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null ) {
                token = buffer.split(delimeter);
                courseId = token[0];
                strm = token[1];
                sessionCode= token[2];
                catalogNbr = cleanCatalogNbr(token[3]);
                classSection = token[4];
                courseTitleLong = token[5];
                langue = token[6];
                acadOrg = token[7];
                strmId = token[8];
                acadCareer = token[9];
                classStat = token[10];
                unitsMinimum = token[11];
                if (token.length >= 13)
                    typeEvaluation = token[12];
                else
                    typeEvaluation = null;
                if (token.length >= 14)
                    instructionMode = token[13];
                else
                    instructionMode = null;

                //Set language
                shortLang = setLangue(langue);

                sectionId = catalogNbr+strmId+classSection;
                enrollmentSetId = sectionId;
                courseOfferingId = catalogNbr+strmId;
                courseSetId = acadOrg;

                //DEBUG MODE
                if (debugMode.isInDebugMode) {
                    if (selectedSessions.contains(strmId) && debugMode.isInDebugCourses(catalogNbr)) {
                        selectedCourses.add(sectionId);
                    }
                } else {
                    selectedCourses.add(sectionId);
                }

                if (selectedSessions.contains(strmId) && selectedCourses.contains(sectionId) ) {
                    //Add active classes
                    if (ACTIVE_SECTION.equalsIgnoreCase(classStat)) {

                        canonicalCourseId = catalogNbr;
                        title = truncateStringBytes(courseTitleLong, MAX_TITLE_BYTE_LENGTH, Charset.forName("utf-8"));
                        description = courseTitleLong;
                        courseSetId = acadOrg;

                        // Create or Update canonical course
                        syncCanonicalCourse(courseSetId, canonicalCourseId, title, description);

                        // Create or Update course offering
                        syncCourseOffering(courseSetId, courseOfferingId, shortLang, typeEvaluation, unitsMinimum, acadCareer,
                                COURSE_OFF_STATUS, title, description, strmId, canonicalCourseId);

                        //Create or Update enrollmentSet
                        syncEnrollmentSet(enrollmentSetId, description, classSection, acadOrg, unitsMinimum, courseOfferingId);

                        //Create or Update section
                        syncSection(sectionId, acadOrg, description, enrollmentSetId, classSection, shortLang,
                                typeEvaluation, courseOfferingId, instructionMode);
                    } else {
                        //Remove the section
                        if (cmService.isSectionDefined(sectionId)) {
                            cmAdmin.removeSection(sectionId);
                            cmAdmin.removeEnrollmentSet(enrollmentSetId);
                            log.info("Supprimer la section " + sectionId);
                        }
                    }
                }
            }
            // ferme le tampon
            breader.close();

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        log.debug("End loadCourses");
    }

    private Section syncSection (String sectionId, String category, String description, String enrollmentSetId,
                                 String sectionTitle, String lang, String typeEvaluation, String courseOfferingId,
                                 String instructionMode){
        Section section = null;
        String instrModeDescription = null, newDescription = null;
        InstructionMode instrMode;


        if (instructionMode != null){
            instrMode = this.instructionMode.get(instructionMode);
            if (lang.equals("fr_CA")){
                instrModeDescription = instrMode.getDescrShort();
            }else{
                instrModeDescription = instrMode.getDescrAng();
            }
        }

        newDescription = description + (instrModeDescription != null? " ( " + instrModeDescription + " )": "");
        if (cmService.isSectionDefined(sectionId)){
            section = cmService.getSection(sectionId);
            section.setCategory(category);
            section.setDescription(newDescription);
            section.setTitle(sectionTitle);
            section.setLang(lang);
            section.setTypeEvaluation(typeEvaluation);
            section.setInstructionMode(instructionMode);
            cmAdmin.updateSection(section);
            log.info(" update section " + sectionId);
        }else{
            section = cmAdmin.createSection(sectionId, sectionTitle, newDescription, category,
                     null, courseOfferingId, enrollmentSetId, lang, typeEvaluation, instructionMode);
            log.info(" create section " + sectionId);
        }
        return section;
    }

    private EnrollmentSet syncEnrollmentSet (String enrollmentSetId, String description, String sectionTitle,
                                             String category, String credits, String courseOfferingId){
        EnrollmentSet enrollmentSet = null;

        if (cmService.isEnrollmentSetDefined(enrollmentSetId)){
            enrollmentSet = cmService.getEnrollmentSet(enrollmentSetId);
            enrollmentSet.setTitle(sectionTitle);
            enrollmentSet.setDescription(description);
            enrollmentSet.setCategory(category);
            enrollmentSet.setDefaultEnrollmentCredits(credits);
            cmAdmin.updateEnrollmentSet(enrollmentSet);
            log.info(" update enrollementSt " + enrollmentSetId);
        } else{
            enrollmentSet = cmAdmin.createEnrollmentSet(enrollmentSetId, sectionTitle, description,
                    category, credits, courseOfferingId, null);
            log.info(" create enrollementSt " + enrollmentSetId);
        }
        return enrollmentSet;
    }

    public String  setLangue(String langStr) {
        String lang = null;
        if (langStr == null || langStr.equals("")
                || langStr.matches("FRAN(�|.+)AIS")) {
            lang = FRENCH;
        } else if (langStr.equals("ANGLAIS")) {
            lang = ENGLISH;
        } else if (langStr.equals("ESPAGNOL")) {
            lang = SPANISH;
        } else {
             lang = FRENCH;
        }

        return lang;
    }

    private void syncCourseOffering (String courseSetId, String courseOfferingId, String lang, String typeEvaluation,
                                               String credits, String acadCareer, String classStatus,String title,
                                               String description, String sessionId, String canonicalCourseId){

        if (syncedCourseOfferings.contains(courseOfferingId)) {
            return;
        }
        CourseOffering courseOffering = null;
        AcademicSession session = cmService.getAcademicSession(sessionId);
        if (cmService.isCourseOfferingDefined(courseOfferingId)){
            courseOffering = cmService.getCourseOffering(courseOfferingId);
            courseOffering.setTitle(title);
            courseOffering.setDescription(description);
            courseOffering.setEndDate(session.getEndDate());
            courseOffering.setStartDate(session.getStartDate());
            courseOffering.setStatus(classStatus);
            courseOffering.setAcademicSession(session);
            courseOffering.setLang(lang);
            courseOffering.setAcademicCareer(acadCareer);
            courseOffering.setCredits(credits);
            cmAdmin.updateCourseOffering(courseOffering);
            log.info("Course offering update " + courseOfferingId);
        }else {
            courseOffering =
            cmAdmin.createCourseOffering(courseOfferingId,
                    title, description, classStatus,
                    session.getEid(), canonicalCourseId,
                    session.getStartDate(), session
                            .getEndDate(), lang, acadCareer,
                    credits, null);
            log.info("Course offering add " + courseOfferingId);

            // Link course offering to course set
            if (cmService.isCourseSetDefined(courseSetId)) {
                cmAdmin.addCourseOfferingToCourseSet(courseSetId, courseOfferingId);
                log.info("Lier le course offering" + courseOfferingId + " au course set " + courseSetId);
            }

        }
        syncedCourseOfferings.add(courseOfferingId);
    }

    private void syncCanonicalCourse(String courseSetId, String canonicalCourseId, String title, String description) {

        if (syncedCanonicalCourses.contains(canonicalCourseId)) {
            return;
        }

        CanonicalCourse course = null;

        if (!cmService.isCanonicalCourseDefined(canonicalCourseId)) {
            course =
                    cmAdmin.createCanonicalCourse(canonicalCourseId,
                            title, description);
            log.info("Create canonical course " + canonicalCourseId);

            // Link canonical course to course set
            if (cmService.isCourseSetDefined(courseSetId)) {
                cmAdmin.addCanonicalCourseToCourseSet(courseSetId, canonicalCourseId);
                log.info("Lier le canonical course " + canonicalCourseId + " au course set " + courseSetId);
            }
        } else {
            course = cmService.getCanonicalCourse(canonicalCourseId);
            course.setDescription(description);
            course.setTitle(title);
            cmAdmin.updateCanonicalCourse(course);
            log.info("Update canonical course " + canonicalCourseId);
        }
        syncedCanonicalCourses.add(canonicalCourseId);
    }

    /**
     * Method used to create instructors and coordinators
     */
    private void loadInstructeurs() {
        log.debug("Start loadInstructeurs");

        String emplId, catalogNbr, strm, sessionCode, classSection, acadOrg, role, strmId;
        String enrollmentSetEid, sectionId;
        try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory + File.separator + PROF_FILE), "ISO-8859-1"));


            instructorsToDelete = getInstructorsInPreviousSynchro();
            coordinatorsToDelete = getCoordinatorsInPreviousSynchro();
            coordinatorsToDeleteForCI = new HashSet<String>();

            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null) {
                token = buffer.split(delimeter);

                emplId = token[0];
                catalogNbr = cleanCatalogNbr(token[1]);
                strm = token[2];
                sessionCode= token[3];
                classSection= token[4];
                role= token[6];

                if (role.equalsIgnoreCase(ENSEIGNANT_ROLE)){
                    acadOrg= token[5];
                    strmId= token[7];
                }else{
                    acadOrg= token[6];
                    role= token[7];
                    strmId= token[8];
                }

                sectionId = catalogNbr+strmId+classSection;
                enrollmentSetEid = sectionId;

                //DEBUG MODE
                if (debugMode.isInDebugMode) {
                    if (selectedSessions.contains(strmId) && debugMode.isInDebugCourses(catalogNbr)) {
                        addOrUpdateProf(role, enrollmentSetEid, emplId);
                    }
                } else {
                    if (selectedSessions.contains(strmId) && selectedCourses.contains(sectionId)) {
                        addOrUpdateProf(role, enrollmentSetEid, emplId);
                    }
                }
            }
            // ferme le tampon
            breader.close();

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        log.debug("End loadInstructeurs");
    }

    private void refreshGroups(String enrollmentSetEid) {
        Set<String> refreshGroups = authzGroupService.getAuthzGroupIds(enrollmentSetEid);
        for (String id : refreshGroups) {
            try {
                authzGroupService.save(authzGroupService.getAuthzGroup(id));
            } catch (GroupNotDefinedException | AuthzPermissionException e) {
                log.error("unable to refresh authz group " + id);
            }
        }
    }

    public void addOrUpdateProf(String role, String enrollmentSetEid, String emplId) {

        if (!cmService.isEnrollmentSetDefined(enrollmentSetEid) || !cmService.isSectionDefined(enrollmentSetEid)) {
            log.warn("The section " + enrollmentSetEid + " does not exist");
            return;
        }

        if (ENSEIGNANT_ROLE.equalsIgnoreCase(role)) {
            //Sans risque puisque le fichier d'extract affiche les enseignants avant les coordonnateurs
            cmAdmin.addOrUpdateSectionMembership(emplId, INSTRUCTOR_ROLE, enrollmentSetEid, ACTIVE_STATUS);
            log.info("Update section " + enrollmentSetEid + " add instructor " + emplId);

            instructorsToDelete.remove(emplId+";"+enrollmentSetEid);
        }
        else if (COORDINATOR_ROLE.equalsIgnoreCase(role)) {

            //Check if user is already instructor
            Set<Membership> instructors = cmService.getSectionMemberships(enrollmentSetEid);
            boolean added = false;
            for (Membership member: instructors){
                if (member.getUserId().equalsIgnoreCase(emplId) && member.getRole().equalsIgnoreCase(INSTRUCTOR_ROLE)) {
                    // if user has already been added as instructor, they should be coordinator-instructor
                    cmAdmin.addOrUpdateSectionMembership(emplId, COORDONNATEUR_INSTRUCTOR_ROLE, enrollmentSetEid, ACTIVE_STATUS);
                    //add user to list to be removed from other sections as coordinator later
                    coordinatorsToDeleteForCI.add(enrollmentSetEid + ";" + emplId);
                    log.info("Update section " + enrollmentSetEid + " add coordinator-instructor " + emplId);
                    added = true;
                }
            }

            if (!added) {
                cmAdmin.addOrUpdateSectionMembership(emplId, COORDONNATEUR_ROLE, enrollmentSetEid, ACTIVE_STATUS);
                log.info("Update section " + enrollmentSetEid + " add coordinator " + emplId);
            }

            coordinatorsToDelete.remove(emplId+";"+enrollmentSetEid);
        }
        // make sure to refresh authzGroups once at the end to ensure correct role
        sectionsToRefresh.add(enrollmentSetEid);
    }

    private void removeCoordinatorInMemberships(String sectionEid, String emplId, String distinctSitesSections){
        if (!cmService.isEnrollmentSetDefined(sectionEid) || !cmService.isSectionDefined(sectionEid)) {
            log.warn("The section " + sectionEid + " does not exist");
            return;
        }
        Section theSection = cmService.getSection(sectionEid);
        String siteId = siteIdFormatHelper.getSiteId(theSection, distinctSitesSections);

        Set<Section> associatedSections = cmService.getSections(theSection.getCourseOfferingEid());
        Set<Membership> coordinators;
        for (Section section: associatedSections) {
            // Check that it's a section in the same site
            if (!siteId.equals(siteIdFormatHelper.getSiteId(section, distinctSitesSections))) {
                continue;
            }

            coordinators = cmService.getSectionMemberships(section.getEid());
            for (Membership member: coordinators) {
                //Mettre a jour son rôle dans la section
                if (member.getUserId().equalsIgnoreCase(emplId) && member.getRole().equalsIgnoreCase(COORDONNATEUR_ROLE)
                        && section.getInstructionMode().equalsIgnoreCase(theSection.getInstructionMode())) {

                    cmAdmin.removeSectionMembership(emplId, section.getEid());
                    log.info("Remove coordinator " + emplId + " from " + section.getEid() + " because they are CI in another section");
                }
            }
        }
    }

    public Set<String> getInstructorsInPreviousSynchro (){
        Set<String> courses = new HashSet<String>();
        Set<String> instructorsBySection = new HashSet <String>();

        courses.addAll(selectedCourses);
        Set<Membership> instructors = new HashSet <Membership>();

        for (String sectionEid: courses){
            if (cmService.isSectionDefined(sectionEid)) {
                instructors = cmService.getSectionMemberships(sectionEid);
                for (Membership member : instructors) {
                    if (member.getRole().equalsIgnoreCase(INSTRUCTOR_ROLE))
                        instructorsBySection.add(member.getUserId() + ";" + sectionEid);
                }
            }
        }
        return instructorsBySection;
    }

    public Set<String> getCoordinatorsInPreviousSynchro (){
        Set<String> courses = new HashSet<String>();
        Set<String> instructorsBySection = new HashSet <String>();

        courses.addAll(selectedCourses);
        Set<Membership> instructors = new HashSet <Membership>();

        for (String sectionEid: courses){
            if (cmService.isSectionDefined(sectionEid)) {
                instructors = cmService.getSectionMemberships(sectionEid);
                for (Membership member : instructors) {
                    if (member.getRole().equalsIgnoreCase(COORDONNATEUR_INSTRUCTOR_ROLE)
                            || member.getRole().equalsIgnoreCase(COORDONNATEUR_ROLE))
                        instructorsBySection.add(member.getUserId() + ";" + sectionEid);
                }
            }
        }
        return instructorsBySection;
    }

    /**
     * Method used to create students
     */
    private void loadEtudiants(){
        log.debug("Start loadEtudiants");
        String emplId, catalogNbr, strm, sessionCode, classSection, status, strmId;
        String sectionId, enrollmentSetEid;

        //get entries from last synchro
        studentEnrollmentsToDelete = getStudentsInPreviousSynchro();
        try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory+ File.separator + ETUDIANT_FILE), "ISO-8859-1"));

            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null) {
                token = buffer.split(delimeter);
                emplId = token[0];
                catalogNbr = cleanCatalogNbr(token[1]);
                strm = token[2];
                sessionCode= token[3];
                classSection= token[4];
                status= token[5];
                strmId= token[6];

               sectionId = catalogNbr+strmId+classSection;
                enrollmentSetEid = sectionId;
                //DEBUG MODE
                if (debugMode.isInDebugMode) {
                    if (selectedSessions.contains(strmId) && debugMode.isInDebugCourses(catalogNbr)) {
                        addOrUpdateEtudiants(sectionId, enrollmentSetEid, emplId);
                        studentEnrollmentsToDelete.remove(emplId + ";" + enrollmentSetEid);
                    }
                } else {
                    if (selectedSessions.contains(strmId) && selectedCourses.contains(sectionId)) {
                        addOrUpdateEtudiants(sectionId, enrollmentSetEid, emplId);
                        studentEnrollmentsToDelete.remove(emplId + ";" + enrollmentSetEid);
                    }
                }
            }
            // ferme le tampon
            breader.close();

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        log.debug("End loadEtudiants");
    }

    public void addOrUpdateEtudiants(String sectionId, String enrollmentSetEid, String emplId){
        if (!cmService.isEnrollmentSetDefined(enrollmentSetEid) || !cmService.isSectionDefined(sectionId)) {
            log.warn("The section " + enrollmentSetEid + " does not exist");
            return;
        }
        EnrollmentSet enrollmentSet = null;
        if (sectionId != null) {
            if (cmService.isEnrollmentSetDefined(enrollmentSetEid)) {
                enrollmentSet = cmService.getEnrollmentSet(enrollmentSetEid);
                cmAdmin.addOrUpdateEnrollment(emplId, enrollmentSetEid, ENROLLMENT_STATUS,
                        enrollmentSet.getDefaultEnrollmentCredits(), GRADING_SCHEME);
                log.info("Create or Update enrollment " + enrollmentSetEid + " for " + emplId);
            }
        }
    }

    public Set<String> getStudentsInPreviousSynchro (){
        Set<String> courses = new HashSet<String>();
        Set<String> studentsBySection = new HashSet <String>();

        courses.addAll(selectedCourses);
        Set<Enrollment> enrollments = null;

        for (String enrollmentSetEid: courses){
            if (cmService.isEnrollmentSetDefined(enrollmentSetEid)) {
                enrollments = cmService.getEnrollments(enrollmentSetEid);
                for (Enrollment enrollment : enrollments)
                    studentsBySection.add(enrollment.getUserId() + ";" + enrollmentSetEid);
            }
        }

        return studentsBySection;
    }

    public void removeEntriesMarkedToDelete(String distinctSitesSections){
        log.debug("Start removeEntriesMarkedToDelete");

        Set<Membership> instructors;

        //Remove outdated students
        String[] values = null;
        for (String entry: studentEnrollmentsToDelete){
            values = entry.split(";");
            cmAdmin.removeEnrollment(values[0], values[1]);
        }

        //Remove outdated instructors
        for (String entry: instructorsToDelete){
            values = entry.split(";");
            instructors = cmService.getSectionMemberships(values[1]);
            for (Membership member: instructors){
                if (member.getRole().equalsIgnoreCase(INSTRUCTOR_ROLE)) {
                    if (member.getUserId().equalsIgnoreCase(values[0])) {
                        cmAdmin.removeSectionMembership(values[0], values[1]);
                        log.info ("Remove instructor " + values[0] + " from section " + values[1]);
                    }
                }
            }
        }

        //Remove outdated coordinators
        for (String entry: coordinatorsToDelete){
            values = entry.split(";");
            instructors = cmService.getSectionMemberships(values[1]);
            for (Membership member: instructors){
                if (member.getRole().equalsIgnoreCase(COORDONNATEUR_INSTRUCTOR_ROLE)
                        || member.getRole().equalsIgnoreCase(COORDONNATEUR_ROLE)) {
                    if (member.getUserId().equalsIgnoreCase(values[0])) {
                        cmAdmin.removeSectionMembership(values[0], values[1]);
                        log.info ("Remove coordinator " + values[0] + " from section " + values[1]);
                    }
                }
            }

         }

         // Delete coordinators that are coordinator-instructor in another section
         for (String entry : coordinatorsToDeleteForCI) {
            values = entry.split(";");
            removeCoordinatorInMemberships(values[0], values[1], distinctSitesSections);
         }

        log.debug("End removeEntriesMarkedToDelete");

    }

    /**
     * Method used to create academic sessions
     */
    private void loadSessions (){
        log.debug("Start loadSessions");

        String acadCareerId, strm, descFrancais, descShortFrancais, descAnglais, descShortAnglais, sessionCode, strmId, title;
        Date beginDate, endDate;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        Date today = new Date();

        List<String> currentSessions = new ArrayList<String>();

        try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory+ File.separator + SESSION_FILE), "ISO-8859-1"));


            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null) {
                token = buffer.split(delimeter);

                acadCareerId = token[0];
                strm = token[1];
                descFrancais = token[2];
                descAnglais = token[3];
                descShortFrancais = token[4];
                descShortAnglais = token[5];
                beginDate = sdf.parse(token[6]);
                endDate = sdf.parse(token[7]);
                sessionCode = token[8];
                strmId = token[9];
                title = descShortFrancais.replace("-","");

                //DEBUG MODE
                if (debugMode.isInDebugMode) {
                    if (debugMode.isInDebugSessions(beginDate, endDate)) {
                        saveOrUpdateSession(strmId, strm, title, descShortAnglais, beginDate, endDate);
                        selectedSessions.add(strmId);
                    }
                }
                else {
                    saveOrUpdateSession(strmId, strm, title, descShortAnglais, beginDate, endDate);
                    selectedSessions.add(strmId);
                }

                if (today.after(beginDate) && today.before(endDate))
                    currentSessions.add(strmId);
            }

            if (currentSessions.size() > 0)
                cmAdmin.setCurrentAcademicSessions(currentSessions);


            // ferme le tampon
            breader.close();

            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ParseException e) {
            e.printStackTrace();
        }

        log.debug("End loadSessions");
    }

    private AcademicSession saveOrUpdateSession(String strmId, String strm, String title, String descShortAnglais, Date beginDate, Date endDate){
        AcademicSession session = null;

        if (cmService.isAcademicSessionDefined(strmId)) {
            session = cmService.getAcademicSession(strmId);
            session.setTitle(title);
            session.setDescription(descShortAnglais);
            session.setStartDate(beginDate);
            session.setEndDate(endDate);
            cmAdmin.updateAcademicSession(session);
            log.info("Update session " + strmId);

            selectedSessions.add(strmId);

        } else {
            session = cmAdmin.createAcademicSession(strmId, title, descShortAnglais, beginDate, endDate);
            log.info("Create session " + strmId);
        }
        System.out.println("la session sera synchronisée: " + strm);


        return session;
    }

    /**
     * Method used to create academic careers.
     */
    public void loadProgEtudes() {
        log.debug("Start loadProgEtudes");

        String acadCareerId, descFrancais, descAnglais;
        AcademicCareer acadCareer = null;

        try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory+ File.separator + PROG_ETUD_FILE), "ISO-8859-1"));


        // We remove the first line containing the title
        breader.readLine();

        // fait le tour des lignes du fichier
        while ((buffer = breader.readLine()) != null) {
            token = buffer.split(delimeter);

            acadCareerId = token[0];
            descFrancais = token[1];
            descAnglais = token[2];

            if (!cmService.isAcademicCareerDefined(acadCareerId)) {
               acadCareer =
                        cmAdmin.createAcademicCareer(acadCareerId, descAnglais,
                               descFrancais);
            } else {
                acadCareer = cmService.getAcademicCareer(acadCareerId);
                acadCareer.setDescription(descAnglais);
                acadCareer.setDescription_fr_ca(descFrancais);
                cmAdmin.updateAcademicCareer(acadCareer);
            }

        }

        // ferme le tampon
        breader.close();

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        log.debug("End loadProgEtudes");
    }

    /**
     * Method used to create categories
     */
    private void loadServEnseignements (){
        log.debug("Start loadServEnseignements");

        String acadOrg, description;
        AcademicSession session;
        try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory+ File.separator + SERV_ENS_FILE), "ISO-8859-1"));


            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null) {
                token = buffer.split(delimeter);

                acadOrg = token[0];
                description = token[1];

                if (cmService.getSectionCategoryDescription (acadOrg) == null) {
                    cmAdmin.addSectionCategory(acadOrg, description);
                }
                try {
                    cmService.getCourseSet(acadOrg);
                }
                catch (IdNotFoundException infe) {
                    cmAdmin.createCourseSet(acadOrg, description, description, null, null);
                }
            }
         // ferme le tampon
            breader.close();

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        log.debug("End loadServEnseignements");
    }

    /**
     * Method used to get instruction mode
     */
    private void loadInstructionMode (){
        log.debug("Start loadInstructionMode");

        String instructionMode, descr, descrShort, descrAng, descrShortAng;
        this.instructionMode = new HashMap<String, InstructionMode>();
         try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory+ File.separator + INSTRUCTION_MODE), "ISO-8859-1"));


            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null) {
                token = buffer.split(delimeter);

                instructionMode = token[0];
                descr = token[1];
                descrShort = token[2];
                descrAng = token[3];
                descrShortAng = token[4];

                this.instructionMode.put(instructionMode, new InstructionMode(instructionMode, descr, descrShort, descrAng, descrShortAng));
            }
            // ferme le tampon
            breader.close();

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        log.debug("End loadInstructionMode");
    }

    /**
     * Truncate a string to a specified length in bytes
     * @param str
     * @param length
     * @return
     */
    private String truncateStringBytes(String str, int length, Charset encoding) {
        if (str == null) {
            return null;
        }

        byte[] bytes = str.getBytes(encoding);

        if (bytes.length < length) {
            return str;
        }

        byte[] ret = new byte[length];

        System.arraycopy(bytes, 0, ret, 0, length - 1);

        String sret = new String(ret, encoding);

        if (sret.length() - 2 < 0) {
            return sret;
        }

        return sret.substring(0, sret.length() - 2);
    }


	private String cleanCatalogNbr(String catalogNbr) {
		return catalogNbr.replaceAll(" ", "");
	}

}

