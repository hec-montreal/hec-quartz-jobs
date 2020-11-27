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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
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
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.user.api.UserNotDefinedException;
import org.springframework.transaction.annotation.Transactional;

import ca.hec.api.SiteIdFormatHelper;
import ca.hec.jobs.api.site.HecExamExceptionGroupSynchroJob;
import lombok.*;

/**
 *
 * @author <a href="mailto:mame-awa.diop@hec.ca">Mame Awa Diop</a>
 * @version $Id: $
 */
public class HecExamExceptionGroupSynchroJobImpl implements HecExamExceptionGroupSynchroJob {

    private static Log log = LogFactory.getLog(HecExamExceptionGroupImpl.class);

    private static Boolean isRunning = false;

    // group property to show it was created by this job
    private static String GROUP_PROP_EXCEPTION_GROUP = "group_prop_exception_group";

    @Setter
    private EmailService emailService;
    @Setter
    private SqlService sqlService;
    @Setter
    protected SiteService siteService;
    @Setter
    protected SessionManager sessionManager;
    @Setter
    protected UserDirectoryService userDirectoryService;
    @Setter
    protected SiteIdFormatHelper siteIdFormatHelper;
    
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Session session = sessionManager.getCurrentSession();
        String distinctSitesSections = context.getMergedJobDataMap().getString("distinctSitesSections");
        String siteId = null;
        String groupTitle = null;
        Site site = null;
        Optional<Group> group = null;

        List<ExceptedStudent> addedStudents = new ArrayList<>();
        List<ExceptedStudent> removedStudents = new ArrayList<>();
        
        if (isRunning) {
            log.error("HecCalendarEventsJob is already running, aborting.");
            return;
        } else {
            isRunning = true;
        }

        log.debug("starting");

        try {
            session.setUserEid("admin");
            session.setUserId("admin");

            String query = "select * from HEC_CAS_SPEC_EXM "
                    + " where (STATE is not null or (STATE is null and GROUPID is null)) and SUBJECT='LANG' "
                    + " order by SUBJECT, CATALOG_NBR, STRM, CLASS_SECTION, N_PRCENT_SUPP";

            List<ExceptedStudent> studentsAdd = sqlService.dbRead(query,
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

                        saveSite(site);
                        deleteFromSyncTable(removedStudents);
                        removedStudents.clear();
                        clearState(addedStudents);
                        addedStudents.clear();

                        try {
                            site = siteService.getSite(siteId);
                        } catch (IdUnusedException e) {
                            site = null;
                            log.info("Site does not exist");
                            continue;
                            //TODO don't even try for the rest of this site's exceptions
                        }
                        log.info("on est dans le site " + siteId);
                    } 

                    groupTitle = generateGroupTitle(student.getClassSection(), student.getNPrcentSupp());
                    group = getGroup(site, groupTitle);

                    try {
                        String studentId = userDirectoryService.getUserId(student.getEmplid());

                        if (!StringUtils.isBlank(student.getState()) && student.getState().equals("A") ||
                            (StringUtils.isBlank(student.getGroupId()) && StringUtils.isBlank(student.getState()))) {                        
                        
                            if (!group.isPresent()) {
                                // TODO add instructors here?
                                group = createGroup(site, groupTitle);
                            }
        
                            log.debug("Add student " + student.getEmplid() + " to group " + groupTitle + " in site " + site.getId());
                            group.get().insertMember(studentId, "Student", true, false);
                            addedStudents.add(student);

                            updateGroupId(student, group.get().getId());
                        } else if (student.getState().equals("D")) {
                            log.debug("Remove student " + student.getEmplid() + " from group " + groupTitle + " in site " + site.getId());
                            group.get().deleteMember(studentId);
                            removedStudents.add(student);
                        }
                    } catch (NoSuchElementException e) {
                        // The optional has no value
                        log.error("Tried to delete from a group that doesn't exist or failed to create group");
                    } catch (IllegalStateException e) {
                        log.error("Unable to modify group because it's locked");
                    } catch (UserNotDefinedException e) {
                        log.error("User does not exist: " + student.getEmplid());
                    }
                }
            }
            // save the last site
            saveSite(site);
            deleteFromSyncTable(removedStudents);
            clearState(addedStudents);
        } finally {
            session.clear();
            isRunning = false;
            log.debug("finished");
        }
    }

    private void deleteFromSyncTable(List<ExceptedStudent> removedStudents) {
        String sql = "delete from HEC_CAS_SPEC_EXM "+
            "where EMPLID=? and CATALOG_NBR=? and STRM=? and CLASS_SECTION=? and N_PRCENT_SUPP=? and STATE = 'D' "; 
            //state = D just to be safe

        for (ExceptedStudent student : removedStudents) {
            sqlService.dbWrite(sql, 
                new Object[] { 
                    student.getEmplid(), 
                    student.getCatalogNbr(), 
                    student.getStrm(), 
                    student.getClassSection(),
                    student.getNPrcentSupp()});
        }
    }

    private void saveSite(Site site) {
        if (site == null) 
            return;

        log.debug("Save site: " + site.getId());
        try {
            // save changes to previous site before retrieving new one
            //maybe implement "regular" groups here before save? idk
            siteService.save(site); 
        } catch (Exception e) {
            log.error("Site save failed", e);
        }
    }

    private boolean updateGroupId(ExceptedStudent student, String groupId) {
        String sql = "update HEC_CAS_SPEC_EXM set GROUPID = ? "+
            "where EMPLID=? and SUBJECT=? and CATALOG_NBR=? and STRM=? and CLASS_SECTION=? and N_PRCENT_SUPP=?";
        return sqlService.dbWrite(sql, 
            new Object[] { groupId,
                student.getEmplid(), 
                student.getSubject(), 
                student.getCatalogNbr(), 
                student.getStrm(), 
                student.getClassSection(),
                student.getNPrcentSupp()});
    }

    private void clearState(List<ExceptedStudent> addedStudents) {
        for (ExceptedStudent student : addedStudents) {
            setState(student, "");
        }
    }

    private boolean setState(ExceptedStudent student, String state) {
        String sql = "update HEC_CAS_SPEC_EXM set STATE = ? "+
            "where EMPLID=? and and CATALOG_NBR=? and STRM=? and CLASS_SECTION=? and N_PRCENT_SUPP=?";

        return sqlService.dbWrite(sql, new Object[] {
            state,
            student.getEmplid(),
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
        group.getProperties().addProperty(Group.GROUP_PROP_WSETUP_CREATED, Boolean.TRUE.toString());
        group.getProperties().addProperty(GROUP_PROP_EXCEPTION_GROUP, Boolean.TRUE.toString());
        return Optional.of(group);
    }
    
    private Optional<Group> getGroup(String siteId, String groupTitle) throws IdUnusedException {
        Site site = siteService.getSite(siteId);
        return getGroup(site, groupTitle);
    }

    private Optional<Group> getGroup(Site site, String groupTitle) {
        return site.getGroups().stream().filter(group -> group.getTitle().equals(groupTitle)).findFirst();
    }
    
    //For now only one type of message because it will probably not be used
    //Later we can refine message structure and translation
    private void notifyError (ExceptedStudent student, String messageType, String siteId) {
	String from = "zonecours@hec.ca";
	String subject = "L'étudiant " + student.getEmplid() + " n'a pas été ajouté à son groupe d'exception " 
			+ " pour le cours " + siteId;
	String to = student.getNListeEmailAdj() + "," + student.getNListeEmailProf() + "," + student.getNListeEmailCoord();
			
	
	
    }
    
    @Data
    private class ExceptedStudent {
        String strm, emplid, nPrcentSupp, acadCareer, subject, catalogNbr, classSection, state, groupId,
        nListeEmailAdj, nListeEmailProf, nListeEmailCoord;
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
                student.setNListeEmailAdj(rs.getString("N_EMAIL_ADJ_PRINC"));
                student.setNListeEmailProf(rs.getString("N_LISTE_EMAIL_PROF"));
                student.setNListeEmailCoord(rs.getString("N_EMAIL_COORD"));
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

