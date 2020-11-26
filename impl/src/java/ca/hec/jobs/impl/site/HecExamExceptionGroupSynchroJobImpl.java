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

import org.apache.commons.lang3.StringUtils;
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
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Session session = sessionManager.getCurrentSession();
        String distinctSitesSections = context.getMergedJobDataMap().getString("distinctSitesSections");
        String siteId = null;
        String groupTitle = null;
        Site site = null;
        Optional<Group> group = null;
        
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
            String order_by = " order by SUBJECT, CATALOG_NBR, STRM, CLASS_SECTION, N_PRCENT_SUPP";

            List<ExceptedStudent> studentsAdd = sqlService.dbRead(select_from + " where STATE is not null or (STATE is null and groupid is null)" + order_by,
                    null, new ExceptedStudentRecord());

            // TODO Ajouter le/les professeurs à la section
            for (ExceptedStudent student : studentsAdd) {
                siteId = siteIdFormatHelper.getSiteId(student.getSubject() + student.getCatalogNbr(), student.getStrm(),
                        SESSION_CODE, student.getClassSection(), distinctSitesSections);

                if (siteId == null) {
                    log.info("Le cours-section n'est pas encore dans le course management " + student.getSubject()
                            + student.getCatalogNbr() + student.getStrm() + SESSION_CODE + student.getClassSection());
                } else {
                    // We have changed site or have just started
                    if (site == null || !siteId.equals(site.getId())) {
                        log.info("on est dans le site " + siteId);

                        saveSite(site);

                        try {
                            site = siteService.getSite(siteId);
                        } catch (IdUnusedException e) {
                            log.info("Site does not exist");
                            //TODO don't even try for the rest of this site's exceptions
                        }
                    } 

                    groupTitle = generateGroupTitle(student.getClassSection(), student.getNPrcentSupp());
                    group = getGroup(site, groupTitle);

                    // TODO only create if it's an add?
                    if (!group.isPresent()) {
                        group = createGroup(site, groupTitle);
                    }

                    if (student.getState().equals("A") || 
                        (StringUtils.isBlank(student.getGroupId()) && StringUtils.isBlank(student.getState()))) {                        
                        
                        log.debug("Add student " + student.getEmplid() + " to group " + groupTitle + " in site " + site.getId());
                        group.get().insertMember(student.getEmplid(), "Student", true, false);
                        updateGroupId(student, group.get().getId());
                    } else if (student.getState().equals("D")) {
                        log.debug("Remove student " + student.getEmplid() + " from group " + groupTitle + " in site " + site.getId());
                        group.get().deleteMember(student.getEmplid());
                    }
                    clearState(student);
                }
                // save the last site
                saveSite(site);
            }
        } finally {
            session.clear();
            isRunning = false;
        }
    }

    private void saveSite(Site site) {
        if (site == null) 
            return;

        log.debug("Save site: " + site.getId());
        try {
            // save changes to previous site before retrieving new one
            //maybe implement "regular" groups here before save? idk
            //disable save for test
            //siteService.save(site); 
        } catch (Exception e) {
            log.error("Site save failed", e);
        }
    }

    private boolean updateGroupId(ExceptedStudent student, String groupId) {
        String sql = "update HEC_CAS_SPEC_EXM set GROUPID = ? "+
            "where EMPLID=? and SUBJECT=? and CATALIG_NBR=? and STRM=? and CLASS_SECTION=? and N_PRCENT_SUPP=?";
        return sqlService.dbWrite(sql, 
            new Object[] { groupId,
                student.getEmplid(), 
                student.getSubject(), 
                student.getCatalogNbr(), 
                student.getStrm(), 
                student.getClassSection(),
                student.getNPrcentSupp()});
    }

    private boolean clearState(String subject, String catalogNbr, String strm, String section) {
        String sql = "update HEC_CAS_SPEC_EXM set STATE = '' "+
            "where SUBJECT=? and CATALOG_NBR=? and STRM=? and CLASS_SECTION=?";
        return sqlService.dbWrite(sql, new Object[] {subject, catalogNbr, strm, section});
    }

    private boolean clearState(ExceptedStudent student) {
        String sql = "update HEC_CAS_SPEC_EXM set STATE = '' "+
            "where EMPLID=? and SUBJECT=? and CATALOG_NBR=? and STRM=? and CLASS_SECTION=? and N_PRCENT_SUPP=?";

        return sqlService.dbWrite(sql, new Object[] {
            student.getEmplid(),
            student.getSubject(),
            student.getCatalogNbr(),
            student.getStrm(),
            student.getClassSection(),
            student.getNPrcentSupp()});
    }

    private String generateGroupTitle(String section, String percent) {
        if (!percent.isEmpty())
            return "AE" + section + percent.replace("%", "");
        else return section + "R";
    }

    private Optional<Group> createGroup(Site site, String groupTitle) {
        log.debug("Create group " + groupTitle + " in site " + site.getId());
        Group group = site.addGroup();
        group.setTitle(groupTitle);
        return Optional.of(group);
    }
    
    private Optional<Group> getGroup(String siteId, String groupTitle) throws IdUnusedException {
        Site site = siteService.getSite(siteId);
        return getGroup(site, groupTitle);
    }

    private Optional<Group> getGroup(Site site, String groupTitle) {
        return site.getGroups().stream().filter(group -> group.getTitle().equals(groupTitle)).findFirst();
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

