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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.Enrollment;
import org.sakaiproject.coursemanagement.api.Membership;
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
    @Setter
    protected CourseManagementService cmService;
    @Setter
    protected ServerConfigurationService serverConfigService;
    
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Session session = sessionManager.getCurrentSession();
        String distinctSitesSections = context.getMergedJobDataMap().getString("distinctSitesSections");
        String subject = context.getMergedJobDataMap().getString("optionalSubject");
        String siteId = null;
        String previousSiteId = null;
        Site site = null;
        Optional<Group> group = null;
        Optional<Group> regularGroup = null;

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
                    + " where STATE is not null and STATE <> 'E'";

            if (StringUtils.isNotBlank(subject)) {
                query += " and SUBJECT=?";
            }
            query += " order by SUBJECT, CATALOG_NBR, STRM, CLASS_SECTION, N_PRCENT_SUPP";

            List<ExceptedStudent> studentExceptions = sqlService.dbRead(query,
                    new Object[] {subject}, new ExceptedStudentRecord());

            for (ExceptedStudent student : studentExceptions) {
                previousSiteId = siteId;
                siteId = siteIdFormatHelper.getSiteId(student.getSubject() + student.getCatalogNbr(), student.getStrm(),
                        SESSION_CODE, student.getClassSection(), distinctSitesSections);

                if (siteId == null) {
                    log.info("Le cours-section n'est pas encore dans le course management " + student.getSubject()
                            + student.getCatalogNbr() + student.getStrm() + SESSION_CODE + student.getClassSection());
                } else {
                    // We have changed site or have just started
                    if (!siteId.equals(previousSiteId)) {
                        if (saveSite(site)) {
                            deleteFromSyncTable(removedStudents);
                            clearState(addedStudents);
                        }
                        removedStudents.clear();
                        addedStudents.clear();

                        try {
                            log.info("On est dans le site " + siteId);
                            site = siteService.getSite(siteId);
                        } catch (IdUnusedException e) {
                            site = null;
                            log.info("Site does not exist");
                        }
                    } 

                    if (site == null) {
                        continue;
                    }

                    String groupTitle = generateGroupTitle(student.getClassSection(), student.getNPrcentSupp());
                    String regularGroupTitle = generateGroupTitle(student.getClassSection(), null);
                    group = getGroupByTitle(site, groupTitle);
                    regularGroup = getGroupByTitle(site, regularGroupTitle);

                    String officialProviderId = siteIdFormatHelper.buildSectionId(
                        student.getSubject() + student.getCatalogNbr(), student.getStrm(), SESSION_CODE, student.getClassSection());

                    if (!regularGroup.isPresent()) {
                        regularGroup = createRegularGroup(site, regularGroupTitle, officialProviderId);
                    }

                    try {
                        String studentId = userDirectoryService.getUserId(student.getEmplid());

                        if (student.getState().equals(STATE_ADD)) {
                        
                            if (!group.isPresent()) {
                                group = createGroup(site, groupTitle);
                                addInstructor(site, group.get(), officialProviderId);
                            }

                            log.debug("Add student " + student.getEmplid() + " to group " + groupTitle + " in site " + site.getId());
                            group.get().insertMember(studentId, "Student", true, false);
                            regularGroup.get().deleteMember(studentId);
                            addedStudents.add(student);
                        } else if (student.getState().equals(STATE_REMOVE)) {
                            log.debug("Remove student " + student.getEmplid() + " from group " + groupTitle + " in site " + site.getId());
                            group.get().deleteMember(studentId);
                            regularGroup.get().insertMember(studentId, "Student", true, false);
                            removedStudents.add(student);
                        }
                    } catch (NoSuchElementException e) {
                        // The optional has no value
                        log.error("Tried to delete from a group that doesn't exist or failed to create group");
                    } catch (IllegalStateException e) {
                        log.error("Unable to modify group because it's locked");
                        //Make sure to send email before updating state
                        notifyError(student, siteId, group.get().getTitle());
                        setState(student, STATE_ERROR);
                    } catch (UserNotDefinedException e) {
                        log.error("User does not exist: " + student.getEmplid());
                    }
                }
            }
            // save the last site
            if (saveSite(site)) {
                deleteFromSyncTable(removedStudents);
                clearState(addedStudents);    
            }
        } finally {
            session.clear();
            isRunning = false;
            log.debug("finished");
        }
    }

    private Optional<Group> createRegularGroup(Site site, String groupTitle, String providerId) {
        Optional<Group> g = createGroup(site, groupTitle);

        // use CM because official group is sometimes not refreshed
        Set<Enrollment> enrollments = cmService.getEnrollments(providerId);

        if (g.isPresent()) {
            for (Enrollment e : enrollments) {
                try {
                    User u = userDirectoryService.getUserByEid(e.getUserId());
                    if (!e.isDropped()) {
                        g.get().addMember(u.getId(), "Student", true, false);
                    }
                }
                catch (UserNotDefinedException unde) {
                    log.error("User not defined: " + e.getUserId());
                }
            }
            addInstructor(site, g.get(), providerId);
        }
        return g;
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

    private boolean saveSite(Site site) {
        if (site == null) 
            return false;

        log.debug("Save site: " + site.getId());
        try {
            siteService.save(site); 
        } catch (Exception e) {
            log.error("Site save failed", e);
            return false;
        }
        return true;
    }

    private void clearState(List<ExceptedStudent> addedStudents) {
        for (ExceptedStudent student : addedStudents) {
            setState(student, null);
        }
    }

    private boolean setState(ExceptedStudent student, String state) {
        String sql = "update HEC_CAS_SPEC_EXM set STATE = ? "+
            "where EMPLID=? and CATALOG_NBR=? and STRM=? and CLASS_SECTION=? and N_PRCENT_SUPP=?";

        return sqlService.dbWrite(sql, new Object[] {
            state,
            student.getEmplid(),
            student.getCatalogNbr(),
            student.getStrm(),
            student.getClassSection(),
            student.getNPrcentSupp()});
    }

    private String generateGroupTitle(String section, String percent) {
        if (StringUtils.isNotEmpty(percent))
            return EXCEPTION_GROUP_PREFIX + section + percent.replace("%", "");
        else return section + REGULAR_GROUP_SUFFIX;
    }

    private Optional<Group> createGroup(Site site, String groupTitle) {
        log.debug("Create group " + groupTitle + " in site " + site.getId());
        Group group = site.addGroup();
        group.setTitle(groupTitle);
        group.getProperties().addProperty(Group.GROUP_PROP_WSETUP_CREATED, Boolean.TRUE.toString());
        group.getProperties().addProperty(GROUP_PROP_EXCEPTION_GROUP, Boolean.TRUE.toString());
        return Optional.of(group);
    }
    
    private Optional<Group> getGroupByTitle(Site site, String title) {
        return site.getGroups().stream()
            .filter(group -> (group.getTitle().equals(title)))
            .findFirst();
    }

    private Optional<Group> getGroupByProviderId(Site site, String providerId) {
        return site.getGroups().stream()
            .filter(group -> (group.getProperties().getProperty(Group.GROUP_PROP_WSETUP_CREATED) == null && group.getProviderGroupId().equals(providerId)))
            .findFirst();
    }

    private void addInstructor(Site site, Group group, String providerId) {
        Optional<Group> providedGroup = getGroupByProviderId(site, providerId);
	    Set<String> addedUsers = null;
        String[] rolesToAdd = null;
    
	    if (providedGroup.isPresent()) {
            try {
                rolesToAdd = serverConfigService
                    .getStrings("hec.eventprocessing.groupeventprocessor.instructor.roles");
                    
	            if (rolesToAdd != null && rolesToAdd.length > 0) {
		            for (int i = 0; i < rolesToAdd.length; i++) {
                        addedUsers = providedGroup.get().getUsersHasRole(rolesToAdd[i]);
                        for (String user : addedUsers) {
                            group.insertMember(user, rolesToAdd[i], true, false);
		                }
		            }
	            }

                // for some reason official instructors can be missing from group 
                // because it's not refreshed from CM so get them there
                Set<Membership> instructors = cmService.getSectionMemberships(providerId);
                for (Membership instructor : instructors) {
                    try {
                        String instructorId = userDirectoryService.getUserId(instructor.getUserId());
                        if (instructor.getRole().equals("I")) {
                            group.insertMember(instructorId, "Instructor", true, false);
                        }
                    } catch (UserNotDefinedException e) {
                        log.warn("the instructor " + instructor.getUserId() + " does not exist");
                    }
                }
            }
            catch (IllegalStateException e) {
                log.error("Unable to modify group because it's locked: " + group.getId());
            }
        }
    }

    // For now only one type of message because it will probably not be used
    // Later we can refine message structure and translation
    private void notifyError(ExceptedStudent student, String siteId, String groupTitle) {
        String from = "zonecours@hec.ca";
        // merge if necessary coordinator and instructor email
        String mergedEmails = (student.getNListeEmailProf().equals(student.getNListeEmailCoord())
                ? student.getNListeEmailCoord()
                : student.getNListeEmailProf() + "," + student.getNListeEmailCoord());

        String action = (student.getState().equals(STATE_REMOVE) ? "retiré d'une" : "ajouté à une");
        String subject = "L'étudiant " + student.getName() + " (" + student.getEmplid() + ") n'a pas été " + action
                + " équipe automatique (" + groupTitle + ") pour le cours " + siteId;
        String to = student.getNListeEmailAdj() + "," + mergedEmails;
        String message = "L’étudiant " + student.getName() + " (" + student.getEmplid() + ") n’a pas été " + action
                + " équipe automatique (" + groupTitle + ") pour le cours " + siteId
                + " parce que l'équipe ne peut être modifiée car elle est actuellement utilisée.\r\n" + "\r\n"
                + "Si le groupe est associé à un travail ou quiz publié et en cours, nous vous conseillons de créer un autre groupe pour cet étudiant. Pour les évaluations à venir, assurez-vous de les publier pour ces nouveaux groupes.\r\n"
                + "Si le groupe est associé à une évaluation  publiée mais pas encore en cours, nous vous suggérons de dépublier l’évaluation et d’ajouter manuellement l’étudiant dans le groupe. Assurez-vous de republier l’évaluation une fois les changements terminés.\r\n"
                + "\r\n" + "Cordialement,\r\n" + "\r\n"
                + "P.S. : Ce courriel est envoyé par un processus automatisé de création de groupes pour les étudiants en situation d’handicap. \r\n";

        emailService.send(from, to, subject, message, null, null, null);
    }
    
    @Data
    private class ExceptedStudent {
        String strm, emplid, name, nPrcentSupp, acadCareer, subject, catalogNbr, classSection, state,
            nListeEmailAdj, nListeEmailProf, nListeEmailCoord;
    }

    private class ExceptedStudentRecord implements SqlReader<ExceptedStudent> {

        @Override
        public ExceptedStudent readSqlResultRecord(ResultSet rs) throws SqlReaderFinishedException {
            ExceptedStudent student = new ExceptedStudent();

            try {
                student.setStrm(rs.getString("STRM"));
                student.setEmplid(rs.getString("EMPLID"));
                student.setName(rs.getString("Name"));
                student.setNPrcentSupp(rs.getString("N_PRCENT_SUPP"));
                student.setAcadCareer(rs.getString("ACAD_CAREER"));
                student.setSubject(rs.getString("SUBJECT"));
                student.setCatalogNbr(rs.getString("CATALOG_NBR"));
                student.setClassSection(rs.getString("CLASS_SECTION"));
                student.setState(rs.getString("STATE"));
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

