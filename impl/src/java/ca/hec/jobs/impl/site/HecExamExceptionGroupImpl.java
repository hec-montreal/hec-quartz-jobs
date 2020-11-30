/******************************************************************************
 * $Id: $
 ******************************************************************************
 *
 * Copyright (c) 2020 The Sakai Foundation, The Sakai Quebec Team.
 *
 * Licensed under the Educational Community License, Version 1.0
 * (the "License"); you may not use this file except in compliance with the
 * License.
 * You may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/ecl1.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/
package ca.hec.jobs.impl.site;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.email.cover.EmailService;

import ca.hec.jobs.api.site.HecExamExceptionGroup;
import lombok.Setter;

/**
 * @author <a href="mailto:mame-awa.diop@hec.ca">Mame Awa Diop</a>
 * @version $Id: $
 */
public class HecExamExceptionGroupImpl implements HecExamExceptionGroup {

    private static Log log = LogFactory.getLog(HecExamExceptionGroupImpl.class);

    @Setter
    private EmailService emailService;
    @Setter
    private SqlService sqlService;
    @Setter
    protected CourseManagementService cmService;

    private static final String NOTIFICATION_EMAIL_PROP =
	    "hec.error.notification.email";

    @Override
    public void execute(JobExecutionContext context)
	    throws JobExecutionException {
	log.info(
		"Début de la job de synchro de PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW contenant les étudiants dans des cas spéciaux pour les examens avec la table HEC_CAS_SPEC_EXM");

	String error_address = ServerConfigurationService
		.getString(NOTIFICATION_EMAIL_PROP, null);

	String sessionId = context.getMergedJobDataMap().getString("sessionId");
	
	if (sessionId == null || sessionId.equals("")) {
	    sessionId = cmService.getCurrentAcademicSessions().get(0).getEid();
	    sessionId = StringUtils.chop(sessionId);
	}

    List<String> checkdata = sqlService.dbRead("select MAX(EMPLID) from PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW");
    if (checkdata.isEmpty()) {
            emailService.send("zonecours2@hec.ca", error_address, "La job de transfert des étudiants en cas d'exceptions a échoué",
                    "Il n'y a pas de données dans la vue de PeopleSoft ZONECOURS2_PS_N_CAS_SPEC_EXMW, aucun transfert de données ne sera effectué",
            null, null, null);
            log.error("Il n'y a pas de données dans la vue de PeopleSoft ZONECOURS2_PS_N_CAS_SPEC_EXMW");
            return;
    }

	log.info("Ajout des nouveaux événements");
	sqlService.dbWrite(
		"insert into HEC_CAS_SPEC_EXM (STRM, EMPLID , NAME, N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION, N_EMAIL_ADJ_PRINC, N_LISTE_EMAIL_PROF, N_EMAIL_COORD, STATE)"
			+ "select STRM, EMPLID , NAME, N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION, N_EMAIL_ADJ_PRINC, N_LISTE_EMAIL_PROF, N_EMAIL_COORD, 'A' "
			+ "from PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW "
			+ "where (STRM, EMPLID , NAME, N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION) not in ("
			+ "select STRM, EMPLID , NAME, N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION from HEC_CAS_SPEC_EXM)"
			+ "and strm= " + sessionId
			+ " order by CATALOG_NBR, CLASS_SECTION, N_PRCENT_SUPP");

	log.info("Marquage des événements supprimés");
	sqlService.dbWrite(
		"update HEC_CAS_SPEC_EXM set STATE = 'D' where (EMPLID, NAME, CATALOG_NBR, STRM, CLASS_SECTION, SUBJECT, N_PRCENT_SUPP) not in "
			+ "(select EMPLID, NAME, CATALOG_NBR, STRM, CLASS_SECTION, SUBJECT, N_PRCENT_SUPP from PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW where strm=" + sessionId + ") "
			+ "and STRM=" + sessionId);

	log.info("Traiter les changements de courriels");
	sqlService.dbWrite(
		"update HEC_CAS_SPEC_EXM t1 set ( N_EMAIL_ADJ_PRINC, N_LISTE_EMAIL_PROF, N_EMAIL_COORD) ="
			+ "                         (select N_EMAIL_ADJ_PRINC, N_LISTE_EMAIL_PROF, N_EMAIL_COORD"
			+ "                         from PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW t2 where"
			+ "                         t2.EMPLID = t1.EMPLID and t2.CATALOG_NBR = t1.CATALOG_NBR"
			+ "                         and t2.STRM = t1.STRM and t2.CLASS_SECTION = t1.CLASS_SECTION"
			+ "                         and t2.SUBJECT = t1.SUBJECT and t2.N_PRCENT_SUPP = t1.N_PRCENT_SUPP) where "
			+ "                         (t1.EMPLID, t1.CATALOG_NBR, t1.STRM, t1.CLASS_SECTION, t1.SUBJECT, t1.N_PRCENT_SUPP, t1.N_EMAIL_ADJ_PRINC, t1.N_LISTE_EMAIL_PROF, t1.N_EMAIL_COORD) not in"
			+ "                         (select t2.EMPLID, t2.CATALOG_NBR, t2.STRM, t2.CLASS_SECTION, t2.SUBJECT, t2.N_PRCENT_SUPP, t2.N_EMAIL_ADJ_PRINC, t2.N_LISTE_EMAIL_PROF, t2.N_EMAIL_COORD"
			+ "                         from PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW t2 ) "
			+ "and STATE != 'D' and STRM=" + sessionId);

	log.info(
		"Fin de la job de synchro du fichier d'extract contenant les événements de cours avec la table HEC_CAS_SPEC_EXM");
    }

}