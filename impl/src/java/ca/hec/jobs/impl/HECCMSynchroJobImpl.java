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
import java.text.DateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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

            loadAcademicCareers();

            loadSessions();

            loadServEnseignements();

            loadCourses();

            loadInstructors();

            loadStudents();

         } finally {
            session.clear();
        }

    }

    /**
     * Method used to create courses
     */
    private void loadCourses (){
        String courseId, strm, sessionCode, catalogNbr, classSection, courseTitleLong, langue, acadOrg, strmId, acadCareer, classStat;
        String unitsMinimum, typeEvaluation, instructionMode, categoryId, description;
        AcademicSession session;
        try {
            synchronizedCourseOfferings = new ArrayList<String>();
            synchronizedSections = new ArrayList<String>();
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory+ File.separator + COURS_FILE), "ISO-8859-1"));


            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null) {
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
                typeEvaluation = token[12];
                //instructionMode = token[13];


                synchronizedSections.add(catalogNbr+strm+classSection);
                synchronizedCourseOfferings.add(catalogNbr+strm);
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
     * Method used to create instructors and coordinators
     */
    private void loadInstructors () {
        String emplId, catalogNbr, strm, sessionCode, classSection, acadOrg, role, strmId;
        AcademicSession session;
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
    private void loadStudents (){
        String emplId, catalogNbr, strm, sessionCode, classSection, status, strmId;
        String sectionId;
        Enrollment enrollment;
        EnrollmentSet enrollmentSet;

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

                sectionId = buildSectionId(catalogNbr, strm, classSection);

                if (sectionId != null){
                    enrollmentSet = cmService.getEnrollmentSet(sectionId);
                    if(enrollmentSet != null){
                        enrollment = cmAdmin.addOrUpdateEnrollment(emplId, enrollmentSet.getEid(), status, "", null);
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

    private String buildSectionId(String catalogNbr, String strm, String section){
        if (catalogNbr == null || strm == null || section == null)
            return null;
        else
            return catalogNbr + strm + section;
    }

    /**
     * Method used to create academic sessions
     */
    private void loadSessions (){
        String acadCareerId, strm, descFrancais, descShortFrancais, descAnglais, descShortAnglais, sessionCode, strmId, title;
        Date beginDate, endDate;
        AcademicSession session;
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

                if ((DateFormat.getDateInstance().parse(A2017_LIMITE).compareTo(beginDate) <= 0))
                    if (cmService.isAcademicSessionDefined(strmId)){
                        System.out.println("update " + strmId);
                        session = cmService.getAcademicSession(strmId);
                        session.setTitle(title);
                        session.setDescription(descShortAnglais);
                        session.setStartDate(beginDate);
                        session.setEndDate(endDate);
                        cmAdmin.updateAcademicSession(session);
                    } else{
                        System.out.println("new " + strmId);
                        cmAdmin.createAcademicSession(strmId, title, descShortAnglais, beginDate, endDate );
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
            } catch (ParseException e) {
            e.printStackTrace();
        }

    }


    /**
     * Method used to create academic careers.
     */
    public void loadAcademicCareers() {
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
                System.out.println(acadCareerId + " new AcadCareer");
            } else {
                System.out.println(acadCareerId + "update AcadCareer");
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
        String categoryId, description;
        AcademicSession session;
        try {
            breader = new BufferedReader(new InputStreamReader(new FileInputStream(
                    directory+ File.separator + SESSION_FILE), "ISO-8859-1"));


            // We remove the first line containing the title
            breader.readLine();

            // fait le tour des lignes du fichier
            while ((buffer = breader.readLine()) != null) {
                token = buffer.split(delimeter);

                categoryId = token[0];
                description = token[1];

                if (cmService.getSectionCategoryDescription (categoryId) == null) {
                    cmAdmin.addSectionCategory(categoryId, description);
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


}
