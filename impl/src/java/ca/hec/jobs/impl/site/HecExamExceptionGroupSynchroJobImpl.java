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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.db.api.SqlReader;
import org.sakaiproject.db.api.SqlReaderFinishedException;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.email.cover.EmailService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.site.api.Group;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;

import ca.hec.api.SiteIdFormatHelper;
import ca.hec.jobs.api.site.HecExamExceptionGroupSynchroJob;
import lombok.Data;
import lombok.Setter;

/**
 *
 * @author <a href="mailto:mame-awa.diop@hec.ca">Mame Awa Diop</a>
 * @version $Id: $
 */
public class HecExamExceptionGroupSynchroJobImpl implements HecExamExceptionGroupSynchroJob {

    private static Log log = LogFactory.getLog(HecExamExceptionGroupImpl.class);

    private static Boolean isRunning = false;

    @Setter
    private EmailService emailService;
    @Setter
    private SqlService sqlService;
    @Setter
    protected SiteService siteService;
    @Setter
    protected SessionManager sessionManager;
    @Setter
    protected SiteIdFormatHelper siteIdFormatHelper;
    
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Session session = sessionManager.getCurrentSession();
        String distinctSitesSections = context.getMergedJobDataMap().getString("distinctSitesSections");
        String siteId = null;
        String previousSiteId = null;
        Site site = null;
        
        if (isRunning) {
            log.error("HecCalendarEventsJob is already running, aborting.");
            return;
        } else {
            isRunning = true;
        }

        try {
            session.setUserEid("admin");
            session.setUserId("admin");

            String select_from = "select STRM, EMPLID , N_PRCENT_SUPP, ACAD_CAREER,"
                    + " SUBJECT, CATALOG_NBR, CLASS_SECTION, STATE, GROUPID from HEC_CAS_SPEC_EXM ";
            String order_by = " order by SUBJECT, CATALOG_NBR, CLASS_SECTION, N_PRCENT_SUPP";

            List<ExceptedStudent> studentsAdd = sqlService.dbRead(select_from + " where STATE is not null" + order_by,
                    null, new ExceptedStudentRecord());

            // Ajouter le/les professeurs à la section
            for (ExceptedStudent student : studentsAdd) {
                siteId = siteIdFormatHelper.getSiteId(student.getSubject() + student.getCatalogNbr(), student.getStrm(),
                        SESSION_CODE, student.getClassSection(), distinctSitesSections);

                if (siteId == null) {
                    log.info("Le cours-section n'est pas encore dans le course management " + student.getSubject()
                            + student.getCatalogNbr() + student.getStrm() + SESSION_CODE + student.getClassSection());
                } else {
                    // We have changed site or have just started
                    if (!siteId.equals(previousSiteId)) {
                        log.info("on est dans le site " + siteId);
                    } else { // We are on the same site as previous iteration
                    }

                }

                previousSiteId = siteId;
            }
        } finally {
            session.clear();
            isRunning = false;
        }

    }

    private String generateGroupTitle(String section, String percent) {
        if (!percent.isEmpty())
            return "AE" + section + percent.replace("%", "");
        else return section + "R";
    }

    private Group createGroup(Site site, String groupTitle) {
        Group group = site.addGroup();
        group.setTitle(groupTitle);
        return group;
    }
    
    private Optional<Group> getGroup(String siteId, String groupTitle) throws IdUnusedException {
        Site site = siteService.getSite(siteId);
        return getGroup(site, groupTitle);
    }

    private Optional<Group> getGroup(Site site, String groupTitle) throws IdUnusedException {
        return site.getGroups().stream().filter(group -> group.getTitle().equals(groupTitle)).findFirst();
    }
    
    public String getSiteId(String subject, String catalogNbr, String classSection) {
	return "";
    }
    @Data
    private class ExceptedStudent {
        String strm, emplid, nPrcentSupp, acadCareer, subject, catalogNbr, classSection, state, groupId;
    }

    private class ExceptedStudentRecord implements SqlReader<ExceptedStudent> {

        @Override
        public ExceptedStudent readSqlResultRecord(ResultSet rs) throws SqlReaderFinishedException {
            ExceptedStudent student = new ExceptedStudent();

            try {
                student.setStrm(rs.getString("STRM"));
                student.setEmplid(rs.getString("EMPLID"));
                student.setNPrcentSupp(rs.getString("N_PRCENT_SUPP"));
                student.setAcadCareer(rs.getString("ACAD_CAREER"));
                student.setSubject(rs.getString("SUBJECT"));
                student.setCatalogNbr(rs.getString("CATALOG_NBR"));
                student.setClassSection(rs.getString("CLASS_SECTION"));
                student.setState(rs.getString("STATE"));
                student.setGroupId(rs.getString("GROUPID"));
            }
            catch (SQLException e) {
                log.error("Error retrieving HecStudent record");
                e.printStackTrace();
                return null;
            }
            return student;
		}

    }


}

