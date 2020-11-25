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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.email.cover.EmailService;

import ca.hec.jobs.api.site.HecExamExceptionGroup;
import lombok.Setter;

/**
 *
 * @author <a href="mailto:mame-awa.diop@hec.ca">Mame Awa Diop</a>
 * @version $Id: $
 */
public class HecExamExceptionGroupImpl implements HecExamExceptionGroup{

    private static Log log = LogFactory.getLog(HecExamExceptionGroupImpl.class);
 
    //Traiter la session active par défaut

    @Setter
    private EmailService emailService;
    @Setter
    private SqlService sqlService;
    
    private static final String NOTIFICATION_EMAIL_PROP = "hec.error.notification.email";

   
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
	log.info("Début de la job de synchro de PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW contenant les étudiants dans des cas spéciaux pour les examens avec la table HEC_CAS_SPEC_EXM");
	
        String error_address = ServerConfigurationService.getString(NOTIFICATION_EMAIL_PROP, null);
        
        String sessionId = context.getMergedJobDataMap().getString("sessionId");

        
        //Valider que toutes les données de la table HEC_CAS_SPEC_EXM sont déjà traitées
	//state == null pour tous les enregistrements
	List<String> activeRecords = sqlService.dbRead("select count(*) from HEC_CAS_SPEC_EXM where STATE is not null");
	Integer nbActiveRecords = Integer.parseInt(activeRecords.get(0));
	
        if ((nbActiveRecords != null ? nbActiveRecords : 0) != 0) {
            emailService.send("zonecours2@hec.ca", error_address, "La job de synchro des étudiants dans des cas spéciaux pour les examensa échoué",
                            "Des événements n'ont pas été traités par la job, "
                            + "la job roulera quand même, sans importer les changements dans HEC_CAS_SPEC_EXM (parce que STATE n'est pas null pour toutes les lignes).",
                    null, null, null);
            log.error("Des événements n'ont pas été traités par la job, "
                    + "la job roulera quand même, sans importer les changements dans HEC_CAS_SPEC_EXM (parce que STATE n'est pas null pour toutes les lignes).");

            throw new JobExecutionException(false);
        }

        log.info("Ajout des nouveaux événements");
        sqlService.dbWrite(
                "insert into HEC_CAS_SPEC_EXM (STRM, EMPLID , N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION , STATE)"
                        + "select STRM, EMPLID , N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION, 'A' "
                        + "from PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW "
                        + "where (STRM, EMPLID , N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION) not in ("
                        + "select STRM, EMPLID , N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION from HEC_CAS_SPEC_EXM)"
                        + "and strm= " + sessionId + " order by CATALOG_NBR, CLASS_SECTION");

        log.info("Marquage et Maj des événements modifiés");
        sqlService.dbWrite(
                "update HEC_CAS_SPEC_EXM t1 set (STRM, EMPLID , N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION , STATE) = "
                        + "		(select STRM, EMPLID , N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION, 'M' "
                        + "		from PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW t2 "
                        + "		where t2.STRM = t1.STRM " + ""
                        + "		and t2.EMPLID = t1.EMPLID"	
                        + "		and t2.ACAD_CAREER = t1.ACAD_CAREER "
                        + "		and t2.SUBJECT = t1.SUBJECT "
                        + "		and t2.CLASS_SECTION =  t1.CLASS_SECTION " 
                        + "		and t2.CATALOG_NBR = t1.CATALOG_NBR) "
                        + "where (STRM, EMPLID , N_PRCENT_SUPP, ACAD_CAREER, SUBJECT, CATALOG_NBR, CLASS_SECTION) in ( "
                        + "		select t2.STRM, t2.EMPLID , t2.N_PRCENT_SUPP, t2.ACAD_CAREER, t2.SUBJECT, t2.CATALOG_NBR, t2.CLASS_SECTION"
                        + "		from PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW  t2 "
                        + "		where t2.CATALOG_NBR = t1.CATALOG_NBR " + "		and t2.STRM = t1.STRM "
                        + "		and t2.CLASS_SECTION =  t1.CLASS_SECTION "
                        + "		and t2.SUBJECT = t1.SUBJECT "
                        + "		and (t1.N_PRCENT_SUPP != t2.N_PRCENT_SUPP))");
        //Valider à part le % qu'est-ce qui peut changer

        log.info("Marquage des événements supprimés");
        sqlService.dbWrite(
                "update HEC_CAS_SPEC_EXM set STATE = 'D' where (EMPLID, CATALOG_NBR, STRM, CLASS_SECTION, SUBJECT, N_PRCENT_SUPP) not in "
                        + "(select EMPLID, CATALOG_NBR, STRM, CLASS_SECTION, SUBJECT, N_PRCENT_SUPP from PSFTCONT.ZONECOURS2_PS_N_CAS_SPEC_EXMW "
                        + "where strm= " + sessionId+ ")" );

        log.info(
                "Fin de la job de synchro du fichier d'extract contenant les événements de cours avec la table HEC_CAS_SPEC_EXM");

	

    }

}

