package ca.hec.jobs.impl.coursemanagement;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Created by 11091096 on 2017-07-11.
 */
public class DebugMode {

    public boolean isInDebugMode = false;
    Date debugSessionStart, debugSessionEnd;
    List<String> debugCourses;

    public DebugMode (String sessionStartDebug, String sessionEndDebug, String coursesDebug){
        if (sessionStartDebug == null || sessionEndDebug == null || coursesDebug == null
                || sessionStartDebug.equalsIgnoreCase("") ||
                sessionEndDebug.equalsIgnoreCase("") ||
                coursesDebug.equalsIgnoreCase("")) {
            isInDebugMode = false;
        }else {
            isInDebugMode = true;
            debugSessionStart = getDate(sessionStartDebug);
            debugSessionEnd = getDate(sessionEndDebug);
            debugCourses = new ArrayList<String>();
            debugCourses.addAll(Arrays.asList(coursesDebug.split(",")));
          }

    }

    public Date getDate(String date) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
        Date convertedDate = null;
        try {
            convertedDate = dateFormat.parse(date);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return convertedDate;

    }

    public boolean isInDebugSessions (Date startDate, Date endDate){
        if (debugSessionStart.before(startDate) && debugSessionEnd.after(endDate))
            return true;
        else
            return false;
    }

    public boolean isInDebugCourses (String courseId){
        return debugCourses.contains(courseId);
    }
}
