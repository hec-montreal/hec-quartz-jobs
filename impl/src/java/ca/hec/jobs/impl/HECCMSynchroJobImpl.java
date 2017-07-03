package ca.hec.jobs.impl;

import ca.hec.jobs.api.HECCMSynchroJob;
import lombok.Data;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.*;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;

import java.io.*;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.*;

/**
 * Created by 11091096 on 2017-04-27.
 */
public class HECCMSynchroJobImpl implements HECCMSynchroJob {




    @Setter
    protected CourseManagementAdministration cmAdmin;
    @Setter
    protected CourseManagementService cmService;

    @Setter
    protected SessionManager sessionManager;

    private static Log log = LogFactory.getLog(HECCMSynchroJobImpl.class);


    private static boolean isRunning = false;

    //File reader variables
    private String directory;
    private String delimeter = ";";
    private String[] token;
    private BufferedReader breader = null;
    private String buffer = null;
    Map<String, Set<EnrollmentSet>> studentEnrollmentSetsToDelete;
    Map<String, Set<String>> instructorsToDelete;
    Map<String, Set<Membership>> coordinatorsToDelete;
    Date startTime, endTime;
    List<String> selectedSessions, selectedCourses;
    Map<String, InstructionMode> instructionMode;



    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {

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
            startTime = new Date();
            selectedSessions = new ArrayList<String>();
            selectedCourses = new ArrayList<String>();

            log.info("Starting HEC CM Data Synchro job at " + startTime);

            loadInstructionMode();

            loadProgEtudes();

            loadSessions();

            loadServEnseignements();

            loadCourses();

            loadInstructeurs();

            loadEtudiants();

            removeEntriesMarkedToDelete();

            endTime = new Date();

            log.info("Ending HEC CM Data Synchro job at " + endTime + " Synchro lasted " + (endTime.getTime()-startTime.getTime()));

        } finally {
            session.clear();
        }

    }

    /**
     * Method used to create courses
     */
    private void loadCourses (){
        String courseId, strm, sessionCode, catalogNbr, classSection, courseTitleLong, langue, acadOrg, strmId, acadCareer, classStat;
        String unitsMinimum, typeEvaluation, instructionMode;
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
                catalogNbr = token[3];
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



                sectionId = catalogNbr.trim()+strm+classSection;
                enrollmentSetId = sectionId;
                courseOfferingId = catalogNbr.trim()+strm;
                courseSetId = acadOrg;

                if (acadCareer.equalsIgnoreCase(CERTIFICAT))
                    selectedCourses.add(sectionId);

                if (selectedSessions.contains(strm) && selectedCourses.contains(sectionId) ) {
                    //Add active classes
                    if (ACTIVE_SECTION.equalsIgnoreCase(classStat)) {

                        canonicalCourseId = catalogNbr.trim();
                        title = truncateStringBytes(courseTitleLong, MAX_TITLE_BYTE_LENGTH, Charset.forName("utf-8"));
                        description = courseTitleLong;
                        courseSetId = acadOrg;

                        //Create or Update canonical course
                        syncCanonicalCourse(canonicalCourseId, title, description);

                        //Link canonical course to course set
                        if (cmService.isCourseSetDefined(courseSetId)) {
                            cmAdmin.removeCanonicalCourseFromCourseSet(courseSetId,
                                    canonicalCourseId);
                            cmAdmin.addCanonicalCourseToCourseSet(courseSetId,
                                    canonicalCourseId);
                            log.info("Lier le canonical course " + canonicalCourseId + " au course set " + courseSetId);
                        }

                        //Create or Update course offering
                        syncCourseOffering(courseOfferingId, langue, typeEvaluation, unitsMinimum, acadCareer, classStat,
                                title, description, strmId, canonicalCourseId);

                        //Link course offering to course set
                        if (cmService.isCourseSetDefined(courseSetId)) {
                            cmAdmin.removeCourseOfferingFromCourseSet(courseSetId,
                                    courseOfferingId);
                            cmAdmin.addCourseOfferingToCourseSet(courseSetId,
                                    courseOfferingId);
                            log.info("Lier le course offering" + courseOfferingId + " au course set " + courseSetId);
                        }

                        //Create or Update enrollmentSet
                        syncEnrollmentSet(enrollmentSetId, description, classSection, acadOrg, unitsMinimum, courseOfferingId);

                        //Create or Update section
                        syncSection(sectionId, acadOrg, description, enrollmentSetId, classSection, langue,
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


    private CourseOffering syncCourseOffering (String courseOfferingId, String lang, String typeEvaluation,
                                               String credits, String acadCareer, String classStatus,String title,
                                               String description, String sessionId, String canonicalCourseId){
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
        }
        return courseOffering;
    }

    private CanonicalCourse syncCanonicalCourse (String canonicalCourseId, String title, String description) {
        CanonicalCourse course = null;

        if (!cmService.isCanonicalCourseDefined(canonicalCourseId)) {
            course =
                    cmAdmin.createCanonicalCourse(canonicalCourseId,
                            title, description);
            log.info("Create canonical course " + canonicalCourseId);
        } else {
            course = cmService.getCanonicalCourse(canonicalCourseId);
            course.setDescription(description);
            course.setTitle(title);
            cmAdmin.updateCanonicalCourse(course);
            log.info("Update canonical course " + canonicalCourseId);
        }
        return course;
    }


    /**
     * Method used to create instructors and coordinators
     */
    private void loadInstructeurs() {
        String emplId, catalogNbr, strm, sessionCode, classSection, acadOrg, role, strmId;
        EnrollmentSet enrollmentSet;
        String enrollmentSetEid, sectionId;
        Set <String> officialInstructors, instructors;
        Set <Membership> coordinators;
        try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory + File.separator + SESSION_FILE), "ISO-8859-1"));


            instructorsToDelete = new HashMap<String, Set<String>>();
            coordinatorsToDelete = new HashMap<String, Set<Membership>>();

            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null) {
                token = buffer.split(delimeter);

                emplId = token[0];
                catalogNbr = token[1];
                strm = token[2];
                sessionCode= token[3];
                classSection= token[4];
                acadOrg= token[5];
                role= token[6];
                strmId= token[7];

                sectionId = catalogNbr.trim()+strm+classSection;
                enrollmentSetEid = sectionId;

                if (selectedSessions.contains(strm) && selectedCourses.contains(sectionId)) {
                    if (coordinatorsToDelete.get(enrollmentSetEid) == null)
                        coordinatorsToDelete.put(enrollmentSetEid, cmService.getSectionMemberships(sectionId));

                    coordinators = coordinatorsToDelete.get(enrollmentSetEid);

                    if (INSTRUCTOR_ROLE.equalsIgnoreCase(role)) {
                        enrollmentSet = cmService.getEnrollmentSet(enrollmentSetEid);
                        officialInstructors = enrollmentSet.getOfficialInstructors();

                        if (instructorsToDelete.get(enrollmentSetEid) == null)
                            instructorsToDelete.put(enrollmentSetEid, officialInstructors);

                        if (officialInstructors == null) {
                            officialInstructors = new HashSet<String>();
                        }
                        officialInstructors.add(emplId);
                        enrollmentSet.setOfficialInstructors(officialInstructors);
                        cmAdmin.updateEnrollmentSet(enrollmentSet);
                        log.info("Update enrollmentSet " + enrollmentSetEid + " avec les official instructors " + officialInstructors.toString());

                        //Update list of instructors to delete
                        instructors = instructorsToDelete.get(enrollmentSetEid);
                        instructors.remove(emplId);
                        if (instructors.size() == 0)
                            instructorsToDelete.remove(enrollmentSetEid);
                        else
                            instructorsToDelete.put(enrollmentSetEid, instructors);

                    }

                    if (COORDINATOR_ROLE.equalsIgnoreCase(role)) {
                        cmAdmin.addOrUpdateSectionMembership(emplId, COORDONNATEUR_ROLE, enrollmentSetEid, ACTIVE_STATUS);
                        log.info("Update enrollmentSet " + enrollmentSetEid + " avec les coordonnateurs " + emplId);

                        //Update list of coordinators to delete
                        for (Membership coordinator : coordinators) {
                            if (coordinator.getUserId().equals(emplId)) {
                                coordinators.remove(coordinator);
                                break;
                            }
                        }
                        if (coordinators.size() > 0)
                            coordinatorsToDelete.remove(enrollmentSetEid);
                        else
                            coordinatorsToDelete.put(enrollmentSetEid, coordinators);

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
    }

    /**
     * Method used to create students
     */
    private void loadEtudiants(){
        String emplId, catalogNbr, strm, sessionCode, classSection, status, strmId;
        String sectionId, enrollmentSetEid;
        EnrollmentSet enrollmentSet;
        Set<EnrollmentSet> tempEnrollmentSet;

        studentEnrollmentSetsToDelete = new HashMap<String, Set<EnrollmentSet>>();

        try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory+ File.separator + ETUDIANT_FILE), "ISO-8859-1"));


            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null) {
                token = buffer.split(delimeter);
                emplId = token[0];
                catalogNbr = (token[1]).trim();
                strm = token[2];
                sessionCode= token[3];
                classSection= token[4];
                status= token[5];
                strmId= token[6];

               sectionId = catalogNbr.trim()+strm+classSection;
                enrollmentSetEid = sectionId;
                if (selectedSessions.contains(strm) && selectedCourses.contains(sectionId)) {
                    if (sectionId != null) {
                        if (cmService.isEnrollmentSetDefined(enrollmentSetEid)) {
                            enrollmentSet = cmService.getEnrollmentSet(enrollmentSetEid);
                            cmAdmin.addOrUpdateEnrollment(emplId, enrollmentSetEid, ENROLLMENT_STATUS, enrollmentSet.getDefaultEnrollmentCredits(), GRADING_SCHEME);
                            log.info("Create or Update enrollment " + enrollmentSetEid + " pour " + emplId);
                            //add user to map of enrollments to delete later
                            if (studentEnrollmentSetsToDelete.get(emplId) == null) {
                                tempEnrollmentSet = cmService.findCurrentlyEnrolledEnrollmentSets(emplId);
                                studentEnrollmentSetsToDelete.put(emplId, tempEnrollmentSet);
                                log.info(" On a ajouté pour " + emplId + " : " + tempEnrollmentSet.size());
                            }

                            //remove the course from the list enrollments to delete
                            tempEnrollmentSet = studentEnrollmentSetsToDelete.get(emplId);
                            tempEnrollmentSet.remove(cmService.getEnrollmentSet(enrollmentSetEid));
                            if (tempEnrollmentSet.size() > 0)
                                studentEnrollmentSetsToDelete.put(emplId, tempEnrollmentSet);
                            else
                                studentEnrollmentSetsToDelete.remove(emplId);

                            log.info(enrollmentSetEid + " a  été enlevé pour " + emplId + " il reste " + tempEnrollmentSet.size());

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

    }

    public void removeEntriesMarkedToDelete(){

        //Remove outdated instructors
        Set<String> instructorKetSet = instructorsToDelete.keySet();
        Set<String> instructors, officialInstructors;
        EnrollmentSet enrollmentSet;
        for (String instrKey: instructorKetSet){
            instructors = instructorsToDelete.get(instrKey);
            enrollmentSet = cmService.getEnrollmentSet(instrKey);
            officialInstructors = enrollmentSet.getOfficialInstructors();
            for(String instructor: instructors){
                officialInstructors.remove(instructor);
            }
            enrollmentSet.setOfficialInstructors(officialInstructors);
            cmAdmin.updateEnrollmentSet(enrollmentSet);
            log.info ("Remove instructor " + enrollmentSet.getEid() + " avec les official instructors " + officialInstructors.toString());
        }

        //Remove outdated coordinators
        Set <String> coordinatorKeySet = coordinatorsToDelete.keySet();
        Set <Membership> coordinators;
        for (String coorKey: coordinatorKeySet){
            coordinators = coordinatorsToDelete.get(coorKey);
            for (Membership coordinator: coordinators){
                cmAdmin.removeSectionMembership(coordinator.getUserId(), coorKey);
                log.info ("Remove coordinator " + coordinator.getUserId() + " dans le site " + coordinator.toString());
            }
        }
        // Remove outdated enrollments
        Set <String> keySet =  studentEnrollmentSetsToDelete.keySet();
        Set<EnrollmentSet> enrollmentSets;
        for (String key: keySet){
            enrollmentSets = studentEnrollmentSetsToDelete.get(key);
            for (EnrollmentSet enrollmSet: enrollmentSets){
                cmAdmin.removeEnrollment(key, enrollmSet.getEid());
                log.info ("Remove student " + enrollmSet.getEid() + " matricule " + key);
            }
        }
    }

    /**
     * Method used to create academic sessions
     */
    private void loadSessions (){
        String acadCareerId, strm, descFrancais, descShortFrancais, descAnglais, descShortAnglais, sessionCode, strmId, title;
        Date beginDate, endDate;
        AcademicSession session;
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
                beginDate = DateFormat.getDateInstance().parse(token[6]);
                endDate = DateFormat.getDateInstance().parse(token[7]);
                sessionCode = token[8];
                strmId = token[9];
                title = descShortFrancais.replace("-","");

                if (endDate.before(DateFormat.getDateInstance().parse(A2017_LIMITE))){
                    if (cmService.isAcademicSessionDefined(strmId)){
                    session = cmService.getAcademicSession(strmId);
                    session.setTitle(title);
                    session.setDescription(descShortAnglais);
                    session.setStartDate(beginDate);
                    session.setEndDate(endDate);
                    cmAdmin.updateAcademicSession(session);
                    log.info("Update session " + strm);

                    selectedSessions.add(strm);

                    } else{
                        session = cmAdmin.createAcademicSession(strmId, title, descShortAnglais, beginDate, endDate );
                        log.info("Create session " + strm);
                    }

                    if (today.after(beginDate) && today.before(endDate))
                        currentSessions.add(strmId);
                }
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

    }


    /**
     * Method used to create academic careers.
     */
    public void loadProgEtudes() {
        String acadCareerId, strm, descFrancais, descAnglais;
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
    }

    /**
     * Method used to create categories
     */
    private void loadServEnseignements (){
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

    }

    /**
     * Method used to get instruction mode
     */
    private void loadInstructionMode (){
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


}

@Data
class InstructionMode{
    String instructionMode, descr, descrShort, descrAng, descrShortAng;

    public InstructionMode(String instructionMode, String descr, String descrShort, String descrAng, String descrShortAng){
        this.instructionMode = instructionMode;
        this.descr = descr;
        this.descrShort = descrShort;
        this.descrAng = descrAng;
        this.descrShortAng = descrShortAng;
    }

}

