package ca.hec.jobs.impl.coursemanagement;

import ca.hec.api.SiteIdFormatHelper;
import ca.hec.jobs.api.coursemanagement.HECCMSynchroJob;
import lombok.AllArgsConstructor;
import lombok.Data;
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
import java.util.stream.Collectors;
import java.util.stream.*;
import java.util.function.Supplier;

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
    private static final String REGISTRAR_EMAIL_PROP = "hec.registrar.error.notification.email";
    private static final String SESSIONS_TO_CHECK = "hec.df-enrollment.numPreviousSessionsToCheck";
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
    String registrarErrorAddress = null;

    Map<String, List<DfInstructor>> dfInstructors;
    Map<String, String> dfInstructionModes;

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        if (isRunning) {
            log.error("Job already running!");
            return;
        }

        isRunning = true;

        error_address = ServerConfigurationService.getString(NOTIFICATION_EMAIL_PROP, null);
        registrarErrorAddress = ServerConfigurationService.getString(REGISTRAR_EMAIL_PROP, null);
        if (registrarErrorAddress != null) {
            registrarErrorAddress += ","+error_address;
        }
        else { registrarErrorAddress = error_address; }

        String sessionStartDebug = jobExecutionContext.getMergedJobDataMap().getString("cmSessionStart");
        String sessionEndDebug = jobExecutionContext.getMergedJobDataMap().getString("cmSessionEnd");
        String coursesDebug = jobExecutionContext.getMergedJobDataMap().getString("cmCourses");
        String distinctSitesSections = jobExecutionContext.getMergedJobDataMap().getString("distinctSitesSections");

        syncedCanonicalCourses = new HashSet<String>();
        syncedCourseOfferings = new HashSet<String>();
        dfInstructors = new HashMap<String, List<DfInstructor>>();
        dfInstructionModes = new HashMap<String, String>();

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

                String key = catalogNbr + ";" + strm + ";" + classSection;
                if (classSection.startsWith("DF") && !dfInstructionModes.containsKey(key)) {
                    // remember instruction mode in case we need it later
                    dfInstructionModes.put(key, instructionMode);
                }

                // don't create DF sections here
                if (selectedSessions.contains(strmId) && selectedCourses.contains(sectionId)) {
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

                        // don't create section/enrollment set for DF, it's handled per student
                        if (!classSection.startsWith("DF") || Integer.parseInt(strm) < 2223) {
                            //Create or Update enrollmentSet
                            syncEnrollmentSet(enrollmentSetId, description, classSection, acadOrg, unitsMinimum, courseOfferingId);

                            //Create or Update section
                            syncSection(sectionId, acadOrg, description, enrollmentSetId, classSection, shortLang,
                                typeEvaluation, courseOfferingId, instructionMode);
                        }
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

    @Data
    @AllArgsConstructor
    class DfInstructor {
        String role;
        String id;
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

                if (classSection.startsWith("DF") && Integer.parseInt(strm) >= 2223) {
                    String key = catalogNbr+";"+strm+";"+classSection;
                    List<DfInstructor> instructors;
                    if (dfInstructors.get(key) == null) {
                        instructors = new ArrayList<DfInstructor>();
                        dfInstructors.put(key, instructors);
                    }
                    else {
                        instructors = dfInstructors.get(key);
                    }
                    instructors.add(new DfInstructor(role, emplId));
                    log.debug(String.format("Add DF instructor to map %s in course %s and session %s.", emplId, catalogNbr, strm));
                    continue;
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
        // load sessions for DF students
        List<String> allSessions = cmService.getAcademicSessions().stream().map(s->{ return s.getEid(); }).collect(Collectors.toList());

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

                String key = catalogNbr+";"+strm+";"+classSection;
                String errorEmailText = null;
                String errorEmailSubject = null;

                // find good section if it's a DF
                if (classSection.startsWith("DF") && Integer.parseInt(strm) >= 2223) {
                    String courseOfferingId = catalogNbr+strmId;
                    Section previousDFSection = null;

                    // retrieve course offering for creating section/enrollmentSet
                    CourseOffering co = cmService.getCourseOffering(courseOfferingId);
                    String courseSetEid = null;
                    if (co.getCourseSetEids().size()>0) {
                        courseSetEid = co.getCourseSetEids().iterator().next();
                    }
                    else {
                        log.error(String.format("Couln't find course set id for DF in course offering %s", courseOfferingId));
                    }

                    // used for identifying the original enrollment of a DF student
                    Integer oldestEligibleSession = getOldestEligibleSession(strmId, allSessions);

                    String desiredInstructionMode = null;
                    previousDFSection = getPreviousSectionForDF(emplId, catalogNbr, oldestEligibleSession);
                    if (previousDFSection == null) {
                        desiredInstructionMode = dfInstructionModes.get(key);

                        errorEmailText = String.format("L'étudiant %s inscrit dans le %s de la session %s n'a pas d'inscription antérieure pour le cours %s dans ZoneCours. On utilise le mode d'enseigment %s, correspondant à son inscription DF.\n",
                            emplId, classSection, strm, catalogNbr, desiredInstructionMode);
                        errorEmailSubject = String.format("Inscription antérieure manquante pour une inscription DF (cheminement %s)", co.getAcademicCareer());
                        log.error(errorEmailText);
                    }
                    else {
                        desiredInstructionMode = translateOldInstructionModes(previousDFSection);
                    }

                    try {
                        String equivalentInstructionMode = null;
                        // check that a section exists with the desired insctruction mode or return an equivalent that does exist
                        equivalentInstructionMode = getDesiredInstructionModeOrEquivalent(courseOfferingId, desiredInstructionMode);

                        if (equivalentInstructionMode == null) {
                            String errorMsg = String.format("ZoneCours ne trouve aucune section pour le cours %s avec un mode d'enseignement acceptable pour l'étudiant %s inscrit dans la section %s à la session %s. L'étudiant sera inscrit dans une nouvelle section avec le mode d'enseigment %s.\n",
                                catalogNbr, emplId, classSection, strm, desiredInstructionMode);
                            log.error(errorMsg);

                            if (errorEmailText != null) {
                                errorEmailText += "\n\n"+errorMsg;
                            }
                            else {
                                errorEmailText = errorMsg;
                                errorEmailSubject = String.format("Aucun site trouvé pour une inscription DF (cheminement %s)", co.getAcademicCareer());
                            }

                            equivalentInstructionMode = desiredInstructionMode;
                        }

                        String sectionTitle = equivalentInstructionMode+"DF";
                        sectionId = catalogNbr+strmId+sectionTitle;

                        // create section and enrollment set if it doesn't exist
                        if (selectedSessions.contains(strmId) &&
                            ((debugMode.isInDebugMode && debugMode.isInDebugCourses(catalogNbr)) || !debugMode.isInDebugMode)) {

                            // add to sections to handle for later, and load students to delete the ones that aren't in the extract 
                            if (!selectedCourses.contains(sectionId)) {
                                selectedCourses.add(sectionId);
                                studentEnrollmentsToDelete.addAll(getStudentsInEnrollmentSet(sectionId));
                            }

                            if (!cmService.isEnrollmentSetDefined(sectionId)) {
                                //Create or Update enrollmentSet
                                syncEnrollmentSet(sectionId, co.getDescription(), sectionTitle, courseSetEid, co.getCredits(), courseOfferingId);
                            }

                            if (!cmService.isSectionDefined(sectionId)) {
                                //Create or Update section
                                syncSection(sectionId, courseSetEid, co.getDescription(), sectionId, sectionTitle,
                                    co.getLang(), "NONEVAL", courseOfferingId, desiredInstructionMode);
                            }
                        }
                    }
                    catch (IdNotFoundException infe) {
                        log.debug("Current sections for course offering not found: "+courseOfferingId);
                        continue;
                    }
                }
                else {
                    sectionId = catalogNbr+strmId+classSection;
                }

                //DEBUG MODE
                if (selectedSessions.contains(strmId) &&
                    ((debugMode.isInDebugMode && debugMode.isInDebugCourses(catalogNbr) ||
                    (!debugMode.isInDebugMode && selectedCourses.contains(sectionId))))) {

                    addOrUpdateEtudiants(sectionId, sectionId, emplId);
                    if (studentEnrollmentsToDelete.contains(emplId + ";" + sectionId)) {
                        studentEnrollmentsToDelete.remove(emplId + ";" + sectionId);
                    }
                    else if (errorEmailText != null) {
                        // only send the email if the student has not previously been enrolled
                        emailService.send("zonecours2@hec.ca", registrarErrorAddress, errorEmailSubject, errorEmailText, null, null, null);
                    }

                    // special case, enroll instructors and coordinators for DF sections
                    if (dfInstructors.containsKey(key)) {
                        List<DfInstructor> instructors = dfInstructors.get(key);
                        for (DfInstructor dfInst : instructors) {
                            // this method removes from the *ToDelete maps itself
                            addOrUpdateProf(dfInst.getRole(), sectionId, dfInst.getId());
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

        log.debug("End loadEtudiants");
    }

    // When retrieving the original enrollment for the DF 
    // we should only go back 3 sessions
    private Integer getOldestEligibleSession(String givenSession, List<String> allSessions)
    {
        // sessions list should be in order
        Integer prevSessionsToCheck = ServerConfigurationService.getInt(SESSIONS_TO_CHECK, 9);
        return Integer.parseInt(allSessions.get(allSessions.lastIndexOf(givenSession)-prevSessionsToCheck).substring(0, 4));
    }

    // this can be removed after A2023
    private String translateOldInstructionModes(Section previousSection) {

        CourseOffering previousSectionCO = cmService.getCourseOffering(previousSection.getCourseOfferingEid());
        final Boolean prevSectionBeforeA2022 = Integer.parseInt(previousSectionCO.getAcademicSession().getEid()) < 22231;
        final String previousInstructionMode = 
            prevSectionBeforeA2022 && previousSection.getTitle().startsWith("HY2") ? "HS" : 
            prevSectionBeforeA2022 && previousSection.getTitle().startsWith("HY3") ? "HA" : 
            prevSectionBeforeA2022 && previousSection.getTitle().startsWith("DI") ? "DS" : 
            prevSectionBeforeA2022 && previousSection.getTitle().startsWith("WE") ? "DA" :
            prevSectionBeforeA2022 && previousSection.getInstructionMode().equals("AL") ? "HS" :
            prevSectionBeforeA2022 && previousSection.getInstructionMode().equals("WW") ? "DS" :
            previousSection.getInstructionMode();

        return previousInstructionMode;
    }

    // return the instruction mode to use for the new student enrollment
    // should be same instruction mode as previous enrollment or an equivalent if it's in the modePriority list
    // Checks that a section already exists
    private String getDesiredInstructionModeOrEquivalent(final String courseOfferingId, final String desiredInstructionMode) {
        if (desiredInstructionMode == null) {
            return null;
        }

        final List<String> modePriority = 
            Arrays.asList(ServerConfigurationService.getString("hec.df-enrollment.equivalence-priority", "P,CM,HS,DS,IS").split(","));
        
        // supplier to get a stream of current sections, filter out DF sections
        Supplier<Stream<Section>> sectionsStreamSupplier = () -> cmService.getSections(courseOfferingId).stream()
            .filter(s -> {return !s.getTitle().startsWith("DF") && !s.getTitle().endsWith("DF");});

        Optional<Section> returnMe = Optional.empty();

        // find current section with same instruction mode
        if (!returnMe.isPresent()) {
            returnMe =
                sectionsStreamSupplier.get()
                    .filter(s -> { return s.getInstructionMode().equals(desiredInstructionMode); } )
                    .findAny();
        }

        // if there isn't one, find equivalent instruction mode (by order of priority listed above)
        if (!returnMe.isPresent() && modePriority.contains(desiredInstructionMode)) {
            returnMe = sectionsStreamSupplier.get()
                .filter(s -> { return modePriority.contains(s.getInstructionMode()); })
                .min(Comparator.comparing(o->modePriority.indexOf(o.getInstructionMode())));
        }
        
        if (returnMe.isPresent()) { 
            log.debug(String.format("Returning instruction mode %s since we found matching section %s (we were looking for %s)", 
                returnMe.get().getInstructionMode(), 
                returnMe.get().getEid(), desiredInstructionMode));
        }
        return returnMe.isPresent() ? returnMe.get().getInstructionMode() : null;
    }

    private Section getPreviousSectionForDF(final String studentId, final String catalogNbr, final Integer oldestEligibleSession) {
        try {
            Set<Section> previousSections = cmService.findEnrolledSections(studentId);
            log.debug(String.format("Searching for %s section for student %s after %d", catalogNbr, studentId, oldestEligibleSession));

            // get a list of previous enrollments ordered by session (including other languages) to return the first result
            final String catNumWithoutLang = catalogNbr.matches(".*[A-Z]") ? catalogNbr.substring(0, catalogNbr.length()-1) : catalogNbr;
            final String languageCode = catalogNbr.matches(".*[A-Z]") ? catalogNbr.substring(catalogNbr.length()-1) : null;

            List<Section> sectionList = previousSections.stream()
                .filter(s -> { 
                    return s.getEid().startsWith(catNumWithoutLang) && 
                        !s.getTitle().startsWith("DF") &&
                        !s.getTitle().endsWith("DF") &&
                        getSessionCode(s.getEid(), catNumWithoutLang) >= oldestEligibleSession; 
                })
                .sorted(new Comparator<Section>() {
                    public int compare(Section s1, Section s2) {
                        Integer sessionCode1 = getSessionCode(s1.getEid(), catNumWithoutLang);
                        Integer sessionCode2 = getSessionCode(s2.getEid(), catNumWithoutLang);
                        String langCode1 = s1.getEid().substring(catNumWithoutLang.length(), catNumWithoutLang.length()+1);
                        String langCode2 = s2.getEid().substring(catNumWithoutLang.length(), catNumWithoutLang.length()+1);

                        if (sessionCode1.equals(sessionCode2)) {
                            if (langCode1 == languageCode) { return 1; }
                            if (langCode2 == languageCode) { return -1; }
                        }
                        return sessionCode2.compareTo(sessionCode1);
                    }
                })
                .collect(Collectors.toList());

            if (sectionList.size() > 0) {
                log.debug(String.format("Returning section %s out of %d options", sectionList.get(0).getEid(), sectionList.size()));
                return sectionList.get(0);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            log.error("Exception getting previous section for DF: " + catalogNbr + " " + studentId);
        }
        return null;
    }

    // return the 4 characters following the catalog number, which is the session code (eg 2221 for H2022)
    private Integer getSessionCode(String sectionEid, String catalogNbr) {
        try {
            if (!Character.isDigit(sectionEid.charAt(catalogNbr.length()))) {
                // treat other languages (character following catalog nbr is not a number)
                return Integer.parseInt(sectionEid.substring(catalogNbr.length()+1, catalogNbr.length()+5));
            }
            else {
                return Integer.parseInt(sectionEid.substring(catalogNbr.length(), catalogNbr.length()+4));
            }
        }
        catch (NumberFormatException e) {
            log.error("Error parsing int");
            e.printStackTrace();
            return null;
        }
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
                for (Enrollment enrollment : enrollments) {
                    if (!enrollment.isDropped()) {
                        studentsBySection.add(enrollment.getUserId() + ";" + enrollmentSetEid);
                    }
                }
            }
        }

        return studentsBySection;
    }

    public Set<String> getStudentsInEnrollmentSet(String enrollmentSetEid) {
        Set<Enrollment> enrollments = null;
        Set<String> students = new HashSet <String>();
        if (cmService.isEnrollmentSetDefined(enrollmentSetEid)) {
            enrollments = cmService.getEnrollments(enrollmentSetEid);
            for (Enrollment enrollment : enrollments) {
                if (!enrollment.isDropped()) {
                    students.add(enrollment.getUserId() + ";" + enrollmentSetEid);
                }
            }
        }
        return students;
    }

    public void removeEntriesMarkedToDelete(String distinctSitesSections){
        log.debug("Start removeEntriesMarkedToDelete");

        Set<Membership> instructors;

        //Remove outdated students
        String[] values = null;
        for (String entry: studentEnrollmentsToDelete){
            values = entry.split(";");
            cmAdmin.removeEnrollment(values[0], values[1]);
            log.info(String.format("Remove student %s from section %s", values[0], values[1]));
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

