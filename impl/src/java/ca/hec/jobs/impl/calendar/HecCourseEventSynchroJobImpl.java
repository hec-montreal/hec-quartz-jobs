package ca.hec.jobs.impl.calendar;

import ca.hec.jobs.api.calendar.HecCourseEventSynchroJob;
import ca.hec.jobs.api.calendar.HecCalendarJobExecutionException;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.db.api.SqlReader;
import org.sakaiproject.db.api.SqlReaderFinishedException;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.email.cover.EmailService;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Job de synchro du fichier d'extract contenant les événements de cours avec la
 * table HEC_EVENT
 * <p>
 * Prérecquis : la table HEC_EVENT ne doit pas contenir d'événements non traités
 * (colonne state non nulle)
 *
 * @author 11183065
 */
public class HecCourseEventSynchroJobImpl implements HecCourseEventSynchroJob {

    private static Log log = LogFactory.getLog(HecCourseEventSynchroJobImpl.class);

    private static final String NOTIFICATION_EMAIL_PROP = "hec.error.notification.email";

    @Setter
    private EmailService emailService;
    @Setter
    private SqlService sqlService;

    @Transactional
    @Override
    public void execute() throws HecCalendarJobExecutionException {

        log.info(
                "Début de la job de synchro de PSFTCONT.ZONECOURS2_PS_N_HORAI_COUR_MW contenant les événements de cours avec la table HEC_EVENT");

        String error_address = ServerConfigurationService.getString(NOTIFICATION_EMAIL_PROP, null);

        // On vérifie que la job de traitement des événements est bien passée en
        // s'assurant que la colonne state est nulle pour toutes les lignes
        List<String> results = sqlService.dbRead("select count(*) from HEC_EVENT where STATE is not null");
        Integer activeHecEvent = Integer.parseInt(results.get(0));

        if ((activeHecEvent != null ? activeHecEvent : 0) != 0) {
            emailService.send("zonecours2@hec.ca", error_address, "La job de synchro des événements d'agenda a échoué",
                            "Des événements n'ont pas été traités par la job de propagation vers l'outil calendrier, "
                            + "la job roulera quand même, sans importer les changements dans HEC_EVENT (parce que STATE n'est pas null pour toutes les lignes).",
                    null, null, null);
            log.error("Des événements n'ont pas été traités par la job de propagation vers l'outil calendrier, "
                    + "la job roulera quand même, sans importer les changements dans HEC_EVENT (parce que STATE n'est pas null pour toutes les lignes).");

            throw new HecCalendarJobExecutionException(true);
        }

        try {
            log.info(
                    "Récupération des sessions disponible dans la vue du calendrier");

            List<String> runSessions = sqlService.dbRead(
                "select distinct strm from PSFTCONT.ZONECOURS2_PS_N_HORAI_COUR_MW", null, new SqlReader<String>() {

                    @Override
                    public String readSqlResultRecord(ResultSet result) throws SqlReaderFinishedException {
                        try {
                            return result.getString("STRM");
                        } catch (SQLException e) {
                            e.printStackTrace();
                            return null;
                        }
                    }
                });

            if (runSessions.size() > 0) {
                log.info("Suppression des événements (HEC_EVENT) des sessions autres que ceux qui sont dans la vue peoplesoft: " + runSessions.toString());
                sqlService.dbWrite("delete from HEC_EVENT where strm not in (select distinct strm from PSFTCONT.ZONECOURS2_PS_N_HORAI_COUR_MW)");
            } else {
                emailService.send("zonecours2@hec.ca", error_address, "La job de synchro des événements d'agenda a échoué",
                        "Il n'y a pas de données dans la vue de PeopleSoft (incapable de déterminer une date minimale des données)",
                null, null, null);
                log.error("Il n'y a pas de données dans la vue de PeopleSoft (incapable de déterminer une date minimale des données)");
                throw new HecCalendarJobExecutionException(false);
            }

            log.info("Ajout des nouveaux événements");
            sqlService.dbWrite(
                    "insert into HEC_EVENT (CATALOG_NBR, STRM, SESSION_CODE, CLASS_SECTION, SEQ, CLASS_EXAM_TYPE, DATE_HEURE_DEBUT, DATE_HEURE_FIN, FACILITY_ID, DESCR_FACILITY, DESCR, STATE)"
                            + "select CATALOG_NBR, STRM, SESSION_CODE, CLASS_SECTION, CLASS_EXAM_SEQ, CLASS_EXAM_TYPE, TO_DATE(N_DATE_HEURE_DEBUT, 'YYYY-MM-DD HH24:MI'), TO_DATE(N_DATE_HEURE_FIN, 'YYYY-MM-DD HH24:MI'), FACILITY_ID, N_DESCR_FACILITY, DESCR, 'A' "
                            + "from PSFTCONT.ZONECOURS2_PS_N_HORAI_COUR_MW "
                            + "where (CATALOG_NBR, STRM, SESSION_CODE, CLASS_SECTION, CLASS_EXAM_SEQ, CLASS_EXAM_TYPE) not in ("
                            + "select CATALOG_NBR, STRM, SESSION_CODE, CLASS_SECTION, SEQ, CLASS_EXAM_TYPE from HEC_EVENT)");

            log.info("Marquage et Maj des événements modifiés");
            sqlService.dbWrite(
                    "update HEC_EVENT t1 set (DATE_HEURE_DEBUT, DATE_HEURE_FIN, FACILITY_ID, DESCR_FACILITY, DESCR, STATE) = "
                            + "		(select TO_DATE(N_DATE_HEURE_DEBUT, 'YYYY-MM-DD HH24:MI'), TO_DATE(N_DATE_HEURE_FIN, 'YYYY-MM-DD HH24:MI'), FACILITY_ID, N_DESCR_FACILITY, DESCR, 'M' "
                            + "		from PSFTCONT.ZONECOURS2_PS_N_HORAI_COUR_MW t2 "
                            + "		where t2.CATALOG_NBR = t1.CATALOG_NBR " + "		and t2.STRM = t1.STRM "
                            + "		and t2.SESSION_CODE = t1.SESSION_CODE "
                            + "		and t2.CLASS_SECTION =  t1.CLASS_SECTION " + "		and t2.CLASS_EXAM_SEQ = t1.SEQ "
                            + "		and t2.CLASS_EXAM_TYPE = t1.CLASS_EXAM_TYPE) "
                            + "where (CATALOG_NBR, STRM, SESSION_CODE, CLASS_SECTION, SEQ, CLASS_EXAM_TYPE) in ( "
                            + "		select t2.CATALOG_NBR, t2.STRM, t2.SESSION_CODE, t2.CLASS_SECTION, t2.CLASS_EXAM_SEQ, t2.CLASS_EXAM_TYPE "
                            + "		from PSFTCONT.ZONECOURS2_PS_N_HORAI_COUR_MW t2 "
                            + "		where t2.CATALOG_NBR = t1.CATALOG_NBR " + "		and t2.STRM = t1.STRM "
                            + "		and t2.SESSION_CODE = t1.SESSION_CODE "
                            + "		and t2.CLASS_SECTION =  t1.CLASS_SECTION " + "		and t2.CLASS_EXAM_SEQ = t1.SEQ "
                            + "		and t2.CLASS_EXAM_TYPE = t1.CLASS_EXAM_TYPE "
                            + "		and (t1.DATE_HEURE_DEBUT != TO_DATE(t2.N_DATE_HEURE_DEBUT, 'YYYY-MM-DD HH24:MI') "
                            + "			or t1.DATE_HEURE_FIN != TO_DATE(t2.N_DATE_HEURE_FIN, 'YYYY-MM-DD HH24:MI') "
                            + "			or t1.FACILITY_ID != t2.FACILITY_ID "
                            + "			or t1.DESCR_FACILITY != t2.N_DESCR_FACILITY "
                            + "			or t1.DESCR != t2.DESCR))");

            log.info("Marquage des événements supprimés");
            sqlService.dbWrite(
                    "update HEC_EVENT set STATE = 'D' where (CATALOG_NBR, STRM, SESSION_CODE, CLASS_SECTION, SEQ, CLASS_EXAM_TYPE) not in "
                            + "(select CATALOG_NBR, STRM, SESSION_CODE, CLASS_SECTION, CLASS_EXAM_SEQ, CLASS_EXAM_TYPE from PSFTCONT.ZONECOURS2_PS_N_HORAI_COUR_MW)");

            log.info(
                    "Fin de la job de synchro du fichier d'extract contenant les événements de cours avec la table HEC_EVENT");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
