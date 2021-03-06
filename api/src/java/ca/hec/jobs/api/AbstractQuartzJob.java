/******************************************************************************
 * $Id: $
 ******************************************************************************
 *
 * Copyright (c) 2011 The Sakai Foundation, The Sakai Quebec Team.
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
package ca.hec.jobs.api;

import org.quartz.Job;
import org.sakaiproject.content.api.ContentResource;

import java.util.List;
import java.util.PropertyResourceBundle;

/**
 *
 * @author <a href="mailto:mathieu.cantin@hec.ca">Mathieu Cantin</a>
 * @version $Id: $
 */
public interface AbstractQuartzJob extends Job {
    
    final static String PROP_SITE_ISFROZEN = "isfrozen";
    
    public final static String COURSE_SITE = "course";
    
    public final static String DIRECTORY_SITE = "directory";
    
    public PropertyResourceBundle getResouceBundle(ContentResource resource);
    
    public List<String> getActiveTerms();
}

