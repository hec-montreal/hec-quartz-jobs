package ca.hec.jobs.impl;

import ca.hec.jobs.api.HECCMSynchroJob;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.AcademicCareer;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseManagementAdministration;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;

import java.io.*;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Date;

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

    private String directory;

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
         } finally {
            session.clear();
        }

    }

    /**
     * Method used to create academic sessions
     */
    private void loadSessions (){
        BufferedReader breader = null;
        String buffer = null;
        String acadCareerId, strm, descFrancais, descShortFrancais, descAnglais, descShortAnglais, sessionCode, strmId, title;
        Date beginDate, endDate;
        String delimeter = ";";
        int i;
        String[] token;
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

                if ((DateFormat.getDateInstance().parse(A2017_LIMITE).compareTo(beginDate) >= 0))
                    if (cmService.isAcademicSessionDefined(strmId)){
                        session = cmService.getAcademicSession(strmId);
                        session.setTitle(title);
                        session.setDescription(descShortAnglais);
                        session.setStartDate(beginDate);
                        session.setEndDate(endDate);
                        cmAdmin.updateAcademicSession(session);
                    } else{
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
        BufferedReader breader = null;
        String buffer = null;
        String acadCareerId, strm, descFrancais, descAnglais;
        AcademicCareer acadCareer = null;
        String delimeter = ";";
        String[] token;

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

            System.out.println(acadCareerId + " AcadCareer");
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
