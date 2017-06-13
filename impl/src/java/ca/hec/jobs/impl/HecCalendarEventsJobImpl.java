/******************************************************************************
 * $Id: $
 ******************************************************************************
 *
 * Copyright (c) 2013 The Sakai Foundation, The Sakai Quebec Team.
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
package ca.hec.jobs.impl;

import ca.hec.commons.utils.FormatUtils;
import ca.hec.jobs.api.HecCalendarEventsJob;
import lombok.Data;
import lombok.Setter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.calendar.api.Calendar;
import org.sakaiproject.calendar.api.CalendarEvent;
import org.sakaiproject.calendar.api.CalendarEventEdit;
import org.sakaiproject.calendar.api.CalendarService;
import org.sakaiproject.coursemanagement.api.CourseManagementAdministration;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.coursemanagement.api.CourseOffering;
import org.sakaiproject.coursemanagement.api.Section;
import org.sakaiproject.coursemanagement.api.exception.IdNotFoundException;
import org.sakaiproject.entity.cover.EntityManager;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.site.api.Group;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.time.cover.TimeService;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.util.ResourceLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;


/**
 * @author curtis.van-osch@hec.ca
 * @version $Id: $
 */
public class HecCalendarEventsJobImpl implements HecCalendarEventsJob {

	private static Log log = LogFactory.getLog(HecCalendarEventsJobImpl.class);
	private static ResourceLoader rb = new ResourceLoader("ca.hec.jobs.bundle.JobMessages");

	private final String EVENT_TYPE_CLASS_SESSION = "Class session";
	private final String EVENT_TYPE_EXAM = "Exam";
	private final String EVENT_TYPE_QUIZ = "Quiz";
	private final String EVENT_TYPE_SPECIAL = "Special event";
	private final String PSFT_EXAM_TYPE_INTRA = "INTR";
	private final String PSFT_EXAM_TYPE_FINAL = "FIN";
	private final String PSFT_EXAM_TYPE_TEST = "TEST";
	private final String PSFT_EXAM_TYPE_QUIZ = "QUIZ";
	private final String CAREER_MBA = "MBA";

	@Setter
	private CalendarService calendarService;
	@Setter
	private JdbcTemplate jdbcTemplate;

	@Setter
	protected CourseManagementAdministration cmAdmin;
	@Setter
	protected CourseManagementService cmService;
	@Setter
	protected SiteService siteService;
	@Setter
	protected SessionManager sessionManager;

	public void setDataSource(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@Transactional
	public void execute(JobExecutionContext context) throws JobExecutionException {
		log.info("starting CreateCalendarEventsJob");
		int addcount = 0, updatecount = 0, deletecount = 0;

		String piloteE2017Courses = context.getMergedJobDataMap().getString("officialSitesPiloteE2017Courses");
		String piloteA2017Courses = context.getMergedJobDataMap().getString("officialSitesPiloteA2017Courses");
		Session session = sessionManager.getCurrentSession();
		try {
			session.setUserEid("admin");
			session.setUserId("admin");

			String select_from = "select CATALOG_NBR, STRM, SESSION_CODE, CLASS_SECTION, CLASS_EXAM_TYPE, SEQ, DATE_HEURE_DEBUT, "
					+ "DATE_HEURE_FIN, DESCR_FACILITY, STATE, DESCR, EVENT_ID from HEC_EVENT ";
			String order_by = " order by CATALOG_NBR, STRM, CLASS_SECTION ";

			List<HecEvent> eventsAdd = jdbcTemplate.query(
					select_from + "where (EVENT_ID is null and ( STATE = 'A'))" + order_by,
					new HecEventRowMapper());

			// keep track of the last event's site id, calendar and courseOffering, so we can use the calendar if it was already found
			Calendar calendar = null;
			String previousSiteId = "";
			boolean calendarFound = false;
			CourseOffering courseOffering = null;
			Group eventGroup = null;
			Site site = null;

			log.info("loop and add " + eventsAdd.size() + " events");
			for (HecEvent event : eventsAdd) {

				//TODO: Remove after tenjin deploy
				//Do not treat if not in pilote
				if (!inE2017Pilote(event.getCatalogNbr(), event.getSessionId(), piloteE2017Courses.split(",")) &&
						!inA2017Pilote(event.getCatalogNbr(), event.getSessionId(), piloteA2017Courses.split(",")))
					continue;

				String siteId = getSiteId(
						event.getCatalogNbr(),
						event.getSessionId(),
						event.getSessionCode());

				String eventId = null;

				if (!siteId.equals(previousSiteId))
				{
					// this is a new site id, calendar not found yet
					calendarFound = false;
					courseOffering = null;

					try {
						calendar = getCalendar(siteId);
						calendarFound = true;

						// retrieve course offering to see if the course is MBA
						site = siteService.getSite(siteId);
						eventGroup = getGroup(site.getGroups(), event.getSection());
						Section section = cmService.getSection(eventGroup.getProviderGroupId());
						courseOffering = cmService.getCourseOffering(section.getCourseOfferingEid());

					} catch (IdNotFoundException e) {
						log.debug("Site " + siteId + " not associated to course management");
					} catch (IdUnusedException e) {
						log.debug("Site or Calendar for " + siteId + " does not exist");
					} catch (PermissionException e) {
						e.printStackTrace();
						return;
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

				// only attempt event creation if the calendar was found
				if (courseOffering != null && calendarFound) {
					boolean createEvent = true;

					if (event.getStartTime().getYear() != event.getEndTime().getYear() ||
							event.getStartTime().getMonth() != event.getEndTime().getMonth() ||
							event.getStartTime().getDate() != event.getEndTime().getDate()) {

						createEvent = false;
						log.debug("Skipping event creation: " + getEventTitle(siteId, event.getExamType(), event.getSequenceNumber()) +
								" for site " + siteId + " (end date is after start date)");
					}

					// don't bother adding the events if this is an MBA site (ZCII-1495) or DF (ZCII-1665)
					// and the event is not a final or mid-term (intratrimestriel) exam
					if ((courseOffering.getAcademicCareer().equals(CAREER_MBA) || siteId.contains("DF")) &&
							!event.getExamType().equals(PSFT_EXAM_TYPE_INTRA) &&
							!event.getExamType().equals(PSFT_EXAM_TYPE_FINAL)) {
						createEvent = false;
						log.debug("Skipping event creation: " + getEventTitle(siteId, event.getExamType(), event.getSequenceNumber()) +
								" for site " + siteId + " (course is MBA or DF and event is not an exam)");
					}

					if (createEvent) {
						eventGroup = getGroup(site.getGroups(), event.getSection());
						eventId = createCalendarEvent(
								calendar,
								event.getStartTime(),
								event.getEndTime(),
								getEventTitle(siteId, event.getExamType(), event.getSequenceNumber()),
								getType(event.getExamType()),
								event.getLocation(),
								event.getDescription(), eventGroup);
					}
				}

				// clear the state in HEC_EVENT regardless
				if (!clearHecEventState(
						eventId,
						event.getCatalogNbr(),
						event.getSessionId(),
						event.getSessionCode(),
						event.getSection(),
						event.getExamType(),
						event.getSequenceNumber())) {

					throw new JobExecutionException();
				}

				if (eventId != null) {
					addcount++;
				}

				previousSiteId = siteId;
			}

			List<HecEvent> eventsUpdate = jdbcTemplate.query(
					select_from + "where (STATE = 'M' or STATE = 'D')" + order_by,
					new HecEventRowMapper());

			log.info("loop and update "+ eventsUpdate.size() + " events");
			for (HecEvent event : eventsUpdate) {

				//TODO: Remove after tenjin deploy
				//Do not treat if not in pilote
				if (!inE2017Pilote(event.getCatalogNbr(), event.getSessionId(), piloteE2017Courses.split(","))&&
						!inA2017Pilote(event.getCatalogNbr(), event.getSessionId(), piloteA2017Courses.split(",")))
					continue;

				String siteId = getSiteId(
						event.getCatalogNbr(),
						event.getSessionId(),
						event.getSessionCode());

				boolean updateSuccess = false;

				if (!siteId.equals(previousSiteId))
				{
					// new site, calendar not yet found
					calendarFound = false;
					courseOffering = null;

					try {
						calendar = getCalendar(siteId);
						calendarFound = true;

						// retrieve course offering to see if the course is MBA
						site = siteService.getSite(siteId);
						eventGroup = getGroup(site.getGroups(), event.getSection());
						Section section = cmService.getSection(eventGroup.getProviderGroupId());
						courseOffering = cmService.getCourseOffering(section.getCourseOfferingEid());

					} catch (IdNotFoundException e) {
						log.debug("Site " + siteId + " not associated to course management");
					} catch (IdUnusedException e) {
						log.debug("Site or Calendar for " + siteId + " does not exist.");
					} catch (PermissionException e) {
						e.printStackTrace();
						return;
					} catch (Exception e) {
						e.printStackTrace();
					}
				}

				if (courseOffering != null && calendarFound) {

					// don't bother adding the events if this is an MBA site (ZCII-1495) or DF (ZCII-1665)
					// and the event is not a final or mid-term (intratrimestriel) exam
					if ((!courseOffering.getAcademicCareer().equals(CAREER_MBA) && !siteId.contains("DF")) ||
							event.getExamType().equals(PSFT_EXAM_TYPE_INTRA) ||
							event.getExamType().equals(PSFT_EXAM_TYPE_FINAL)) {
						eventGroup = getGroup(site.getGroups(), event.getSection());
						updateSuccess = updateCalendarEvent(
								calendar,
								event.getEventId(),
								event.getState(),
								event.getStartTime(),
								event.getEndTime(),
								event.getLocation(),
								event.getDescription(), eventGroup);
					} else {
						log.debug("Skipping event update: " + getEventTitle(siteId, event.getExamType(), event.getSequenceNumber()) +
								" for site " + siteId + " (course is MBA or DF and event is not an exam)");
					}
				}

				if (event.getState().equals("M")) {
					if (!clearHecEventState(
							event.getEventId(),
							event.getCatalogNbr(),
							event.getSessionId(),
							event.getSessionCode(),
							event.getSection(),
							event.getExamType(),
							event.getSequenceNumber())) {
						throw new JobExecutionException();
					}

					if (updateSuccess)
						updatecount++;
				}
				else if (event.getState().equals("D")) {
					if (!deleteHecEvent(
							event.getCatalogNbr(),
							event.getSessionId(),
							event.getSessionCode(),
							event.getSection(),
							event.getExamType(),
							event.getSequenceNumber())) {
						throw new JobExecutionException();
					}

					if (updateSuccess)
						deletecount++;
				}

				previousSiteId = siteId;
			}
		} finally {
			session.clear();
		}
		log.info("added: " + addcount + " updated: " + updatecount + " deleted: " + deletecount);
	} // execute

	private boolean clearHecEventState(String event_id, String catalog_nbr, String session_id, String session_code, String section,
									   String exam_type, Integer sequence_num) {

		try {
			int affectedRows = jdbcTemplate.update("update HEC_EVENT set STATE = null, EVENT_ID = ? where CATALOG_NBR = ? and STRM = ? and " +
							"SESSION_CODE = ? and CLASS_SECTION = ? and CLASS_EXAM_TYPE = ? and SEQ = ?",
					new Object[] {event_id, catalog_nbr, session_id, session_code, section, exam_type, sequence_num});

			if (affectedRows != 1) {
				return false;
			} else {
				return true;
			}

		} catch (DataAccessException e) {
			e.printStackTrace();
			return false;
		}
	}

	private boolean deleteHecEvent(String catalog_nbr, String session_id, String session_code, String section,
								   String exam_type, Integer sequence_num) {

		try {
			int affectedRows = jdbcTemplate.update("delete from HEC_EVENT where CATALOG_NBR = ? and STRM = ? and SESSION_CODE = ? and CLASS_SECTION = ? " +
							"and CLASS_EXAM_TYPE = ? and SEQ = ?",
					new Object[] {catalog_nbr, session_id, session_code, section, exam_type, sequence_num });

			if (affectedRows != 1) {
				return false;
			} else {
				return true;
			}

		} catch (DataAccessException e) {
			e.printStackTrace();
			return false;
		}
	}

	private String createCalendarEvent(Calendar calendar, Date startTime, Date endTime, String title, String type, String location, String description, Group sectionGroup)
	{
		CalendarEvent event;

		List<Group> groups = new ArrayList<>();
		if (sectionGroup != null && sectionGroup.getProviderGroupId() != null)
			groups.add(sectionGroup);

		try {
			// add event to calendar
			event = calendar.addEvent(
					TimeService.newTimeRange(TimeService.newTime(startTime.getTime()), TimeService.newTime(endTime.getTime()), true, false),
					title,
					description,
					type,
					location,
					CalendarEvent.EventAccess.GROUPED,
					groups,
					EntityManager.newReferenceList());

		} catch (PermissionException e) {
			e.printStackTrace();
			return null;
		}

		log.debug("created event: " + title + " in site " + calendar.getContext());

		return event.getId();
	}

	private boolean updateCalendarEvent(Calendar calendar, String eventId, String state,
										Date newStartTime, Date newEndTime, String newLocation, String newDescription, Group sectionGroup)
	{
		if (eventId == null)
			return false;

		CalendarEventEdit edit;

		try {
			edit = calendar.getEditEvent(eventId, CalendarService.EVENT_MODIFY_CALENDAR);
		} catch (IdUnusedException e) {
			log.debug("Event " + eventId + " does not exist");
			return false;
		} catch (NullPointerException e) {
			log.debug("Event " + eventId + " does not exist");
			return false;
		} catch (Exception e) {
			log.error("Error retrieving event " + eventId);
			e.printStackTrace();
			return false;
		}

		if (state.equals("M")) {
			if (newStartTime != null && newEndTime != null)
				edit.setRange(TimeService.newTimeRange(TimeService.newTime(newStartTime.getTime()), TimeService.newTime(newEndTime.getTime()), true, false));
			if (newLocation != null)
				edit.setLocation(newLocation);
			if (newDescription != null)
				edit.setDescription(newDescription);
		}
		else if (state.equals("D")) {
			try {
				calendar.removeEvent(edit);
			} catch (PermissionException e) {
				log.error("User doesn't have permission to delete event " + eventId);
				return false;
			}
		}

		//Make sure we update group
		try {
			List<Group> groups = new ArrayList<>();
			groups.add(sectionGroup);
			edit.setGroupAccess(groups, true );
		} catch (PermissionException e) {
			log.error("User doesn't have permission to delete event " + eventId);
		}


		calendar.commitEvent(edit);
		log.debug("updated ("+state+") event: " + edit.getDisplayName() + " in site " + calendar.getContext());

		return true;
	}

	private Calendar getCalendar(String siteId) throws IdUnusedException, PermissionException {
		if (siteService.siteExists(siteId)) {
			String calRef = calendarService.calendarReference(siteId, siteService.MAIN_CONTAINER);
			return calendarService.getCalendar(calRef);
		} else {
			throw new IdUnusedException("Site does not exist");
		}
	}

	private String getSiteId(String catalog_nbr, String session_id, String session_code) {
		String siteId = FormatUtils.formatCourseId(catalog_nbr);
		siteId += "." + FormatUtils.getSessionName(session_id);

		if (!session_code.equals("1"))
			siteId += "." + session_code;

		return siteId;
	}

	private String getEventTitle(String siteId, String type, Integer seq_num) {

		Site site = null;
		String courseSiteTittle = "";

		try{
			site = siteService.getSite(siteId);
			courseSiteTittle = site.getProperties().getPropertyFormatted("title");
		}catch (IdUnusedException e){
			log.error("The site " + siteId + "does not exist");
		}


		if (type.equals(" ")){
			if (courseSiteTittle != "")
				return (courseSiteTittle + " (" + rb.getFormattedMessage("calendar.event-title.session", new Object[] { seq_num, siteId }) + ")");
			else
				return rb.getFormattedMessage("calendar.event-title.session", new Object[] { seq_num, siteId });
		}
		else if (type.equals(PSFT_EXAM_TYPE_INTRA)){
			if (courseSiteTittle != "")
				return (courseSiteTittle + " (" + rb.getFormattedMessage("calendar.event-title.intra", new Object[] { siteId }) + ")");
			else
				return rb.getFormattedMessage("calendar.event-title.intra", new Object[] { siteId });
		}
		else if (type.equals(PSFT_EXAM_TYPE_FINAL)){
			if (courseSiteTittle != "")
				return (courseSiteTittle + " (" + rb.getFormattedMessage("calendar.event-title.final", new Object[] { siteId }) + ")");
			else
				return rb.getFormattedMessage("calendar.event-title.final", new Object[] { siteId });
		}
		else if (type.equals(PSFT_EXAM_TYPE_TEST) || type.equals(PSFT_EXAM_TYPE_QUIZ)){
			if (courseSiteTittle != "")
				return (courseSiteTittle + " (" + rb.getFormattedMessage("calendar.event-title.test", new Object[] { siteId }) + ")");
			else
				rb.getFormattedMessage("calendar.event-title.test", new Object[] { siteId });
		}

		else{
			if (courseSiteTittle != "")
				return (courseSiteTittle + " (" + rb.getFormattedMessage("calendar.event-title.other", new Object[] { type, siteId }) + ")");
		}

		return rb.getFormattedMessage("calendar.event-title.other", new Object[] { type, siteId });

	}

	private String getType(String exam_type) {
		if (exam_type.equals(" "))
			return EVENT_TYPE_CLASS_SESSION;
		else if (exam_type.equals(PSFT_EXAM_TYPE_INTRA) || exam_type.equals(PSFT_EXAM_TYPE_FINAL))
			return this.EVENT_TYPE_EXAM;
		else if (exam_type.equals(PSFT_EXAM_TYPE_TEST) || exam_type.equals(PSFT_EXAM_TYPE_QUIZ))
			return EVENT_TYPE_QUIZ;
		else
			return EVENT_TYPE_SPECIAL;
	}

	@Data
	private class HecEvent {
		String catalogNbr, sessionId, sessionCode, section, state, examType, location, description, eventId;
		Integer sequenceNumber;
		Date startTime, endTime;
	}

	private class HecEventRowMapper implements RowMapper {
		@Override
		public HecEvent mapRow(ResultSet rs, int rowNum)
				throws SQLException {

			HecEvent event = new HecEvent();
			event.setCatalogNbr(rs.getString("CATALOG_NBR"));
			event.setSessionId(rs.getString("STRM"));
			event.setSessionCode(rs.getString("SESSION_CODE"));
			event.setSection(rs.getString("CLASS_SECTION"));
			event.setExamType(rs.getString("CLASS_EXAM_TYPE"));
			event.setSequenceNumber(rs.getInt("SEQ"));
			event.setStartTime(rs.getTimestamp("DATE_HEURE_DEBUT"));
			event.setEndTime(rs.getTimestamp("DATE_HEURE_FIN"));
			event.setLocation(rs.getString("DESCR_FACILITY"));
			event.setDescription(rs.getString("DESCR"));
			event.setEventId(rs.getString("EVENT_ID"));
			event.setState(rs.getString("STATE"));

			return event;
		}
	}

	private Group getGroup (Collection<Group> siteGroups, String eventSection){
		String providerId = null;
		for (Group siteGroup: siteGroups) {
			providerId = siteGroup.getProviderGroupId();
			if (providerId != null && providerId.endsWith(eventSection))
				return siteGroup;
		}

		return null;
	}

	private boolean inE2017Pilote (String courseId, String sessioId, String [] piloteE2017){
		for (String exception: piloteE2017){
			if (courseId.equals(exception) && sessioId.equals("2172"))
				return true;
		}
		return false;
	}

	private boolean inA2017Pilote (String courseId, String sessioId, String [] piloteA2017){
		for (String exception: piloteA2017){
			if (courseId.equals(exception) && sessioId.equals("2173"))
				return true;
		}
		return false;
	}
}