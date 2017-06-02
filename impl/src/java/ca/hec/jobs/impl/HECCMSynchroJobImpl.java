package ca.hec.jobs.impl;

import ca.hec.jobs.api.HECCMSynchroJob;
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
    List<String> synchronizedSections;
    List<String> synchronizedCourseOfferings;
    Set <String> currentStudentPerSection;
    Set <String> addedStudentPerSection;


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
            System.out.println("Starting HEC CM Data Synchro job");

            loadProgEtudes();

            loadSessions();

            loadServEnseignements();

            loadCourses();

            loadInstructeurs();

            addedStudentPerSection = new HashSet<String>();
            loadEtudiants();
            currentStudentPerSection = cmService.findCurrentEnrollmentIds();
            System.out.println (" la taille " + currentStudentPerSection.size());
            currentStudentPerSection.removeAll(addedStudentPerSection);
            System.out.println (" la taille apres " + currentStudentPerSection.size());

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
        AcademicSession session;
        CanonicalCourse canonicalCourse;
        CourseOffering courseOffering;
        Section section;
        EnrollmentSet enrollmentSet;
        String sectionId, enrollmentSetId, courseOfferingId, courseSetId, canonicalCourseId, title, description;
        int compte = 0;
        try {
            synchronizedCourseOfferings = new ArrayList<String>();
            synchronizedSections = new ArrayList<String>();
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory+ File.separator + COURS_FILE), "ISO-8859-1"));


            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null && compte <= 5) {
                token = buffer.split(delimeter);
                compte++;
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

                synchronizedSections.add(sectionId);
                synchronizedCourseOfferings.add(courseOfferingId);
                //Add active classes
                if (ACTIVE_SECTION.equalsIgnoreCase(classStat)){

                    canonicalCourseId = catalogNbr.trim();
                    title = truncateStringBytes(courseTitleLong, MAX_TITLE_BYTE_LENGTH, Charset.forName("utf-8"));
                    description = courseTitleLong;
                    courseSetId = acadOrg;

                    //Create or Update canonical course
                    canonicalCourse = syncCanonicalCourse(canonicalCourseId, title, description);

                    //Link canonical course to course set
                    if (cmService.isCourseSetDefined(courseSetId)) {
                        cmAdmin.removeCanonicalCourseFromCourseSet(courseSetId,
                                canonicalCourseId);
                        cmAdmin.addCanonicalCourseToCourseSet(courseSetId,
                                canonicalCourseId);
                    }

                    //Create or Update course offering
                   courseOffering =  syncCourseOffering (courseOfferingId, langue, typeEvaluation, unitsMinimum, acadCareer, classStat,
                            title, description,strmId, instructionMode, canonicalCourseId);

                    //Link course offering to course set
                    if (cmService.isCourseSetDefined(courseSetId)) {
                        cmAdmin.removeCourseOfferingFromCourseSet(courseSetId,
                                courseOfferingId);
                        cmAdmin.addCourseOfferingToCourseSet(courseSetId,
                                courseOfferingId);
                    }

                    //Create or Update enrollmentSet
                    enrollmentSet = syncEnrollmentSet(enrollmentSetId,description, classSection, acadOrg, unitsMinimum, courseOfferingId);

                    //Create or Update section
                    section = syncSection(sectionId, acadOrg, description, enrollmentSetId, classSection, langue, typeEvaluation, courseOfferingId);
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
                                 String sectionTitle, String lang, String typeEvaluation, String courseOfferingId){
        Section section = null;

        if (cmService.isSectionDefined(sectionId)){
            section = cmService.getSection(sectionId);
            section.setCategory(category);
            section.setDescription(description);
            section.setTitle(sectionTitle);
            section.setLang(lang);
            section.setTypeEvaluation(typeEvaluation);
            cmAdmin.updateSection(section);
            System.out.println(" update section " + sectionId);
        }else{
            section = cmAdmin.createSection(sectionId, sectionTitle, description, category,
                     null, courseOfferingId, enrollmentSetId, lang, typeEvaluation);
            System.out.println(" create section " + sectionId);
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
            System.out.println(" update enrollementSt " + enrollmentSetId);
        } else{
            enrollmentSet = cmAdmin.createEnrollmentSet(enrollmentSetId, sectionTitle, description,
                    category, credits, courseOfferingId, null);
            System.out.println(" create enrollementSt " + enrollmentSetId);
        }
        return enrollmentSet;
    }


    private CourseOffering syncCourseOffering (String courseOfferingId, String lang, String typeEvaluation,
                                               String credits, String acadCareer, String classStatus,String title,
                                               String description, String sessionId, String instructionMode,
                                               String canonicalCourseId){
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
            courseOffering.setInstructionMode(instructionMode);
            cmAdmin.updateCourseOffering(courseOffering);
            System.out.println("Course offering update " + courseOfferingId);
        }else {
            courseOffering =
            cmAdmin.createCourseOffering(courseOfferingId,
                    title, description, classStatus,
                    session.getEid(), canonicalCourseId,
                    session.getStartDate(), session
                            .getEndDate(), lang, acadCareer,
                    credits, null, instructionMode);
            System.out.println("Course offering add " + courseOfferingId);
        }
        return courseOffering;
    }

    private CanonicalCourse syncCanonicalCourse (String canonicalCourseId, String title, String description) {
        CanonicalCourse course = null;

        if (!cmService.isCanonicalCourseDefined(canonicalCourseId)) {
            course =
                    cmAdmin.createCanonicalCourse(canonicalCourseId,
                            title, description);
            System.out.println("Create canonical course " + canonicalCourseId);
        } else {
            course = cmService.getCanonicalCourse(canonicalCourseId);
            course.setDescription(description);
            course.setTitle(title);
            cmAdmin.updateCanonicalCourse(course);
            System.out.println("Update canonical course " + canonicalCourseId);
        }
        return course;
    }


    /**
     * Method used to create instructors and coordinators
     */
    private void loadInstructeurs() {
        String emplId, catalogNbr, strm, sessionCode, classSection, acadOrg, role, strmId;
        EnrollmentSet enrollmentSet;
        String enrollmentSetId, sectionId;
        Set <String> officialInstructors;
        try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory + File.separator + SESSION_FILE), "ISO-8859-1"));


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
                enrollmentSetId = sectionId;

                if (INSTRUCTOR_ROLE.equalsIgnoreCase(role)){
                    enrollmentSet = cmService.getEnrollmentSet(enrollmentSetId);
                    officialInstructors = enrollmentSet.getOfficialInstructors();
                    if (officialInstructors == null){
                        officialInstructors = new HashSet <String> ();
                    }
                    officialInstructors.add(emplId);
                    enrollmentSet.setOfficialInstructors(officialInstructors);
                    cmAdmin.updateEnrollmentSet(enrollmentSet);
                }

                if (COORDINATOR_ROLE.equalsIgnoreCase(role)){
                    cmAdmin.addOrUpdateSectionMembership(emplId, COORDONNATEUR_ROLE, enrollmentSetId,ACTIVE_STATUS);
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
        String sectionId, enrollmentSetId;
        EnrollmentSet enrollmentSet;
        String enrollmentId;
        int count = 0;

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
                enrollmentSetId = sectionId;

                if (sectionId != null){
                    if(cmService.isEnrollmentSetDefined(enrollmentSetId)){
                        enrollmentSet = cmService.getEnrollmentSet(enrollmentSetId);
                        cmAdmin.addOrUpdateEnrollment(emplId, enrollmentSetId, ENROLLMENT_STATUS, enrollmentSet.getDefaultEnrollmentCredits(), GRADING_SCHEME);
                        enrollmentId = cmService.findEnrollmentId(emplId, enrollmentSetId);
                        count++;
                        addedStudentPerSection.add(enrollmentId);
                    }

                }




            }

            System.out.println("on a mis à jour " + count + " enrollment");
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

                if (cmService.isAcademicSessionDefined(strmId)){
                    session = cmService.getAcademicSession(strmId);
                    session.setTitle(title);
                    session.setDescription(descShortAnglais);
                    session.setStartDate(beginDate);
                    session.setEndDate(endDate);
                    cmAdmin.updateAcademicSession(session);
                } else{
                    session = cmAdmin.createAcademicSession(strmId, title, descShortAnglais, beginDate, endDate );
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

