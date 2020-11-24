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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.email.cover.EmailService;

import ca.hec.jobs.api.site.HecExamExceptionGroupSynchroJob;
import lombok.Setter;

/**
 *
 * @author <a href="mailto:mame-awa.diop@hec.ca">Mame Awa Diop</a>
 * @version $Id: $
 */
public class HecExamExceptionGroupSynchroJobImpl implements HecExamExceptionGroupSynchroJob {

    private static Log log = LogFactory.getLog(HecExamExceptionGroupImpl.class);
    

    @Setter
    private EmailService emailService;
    @Setter
    private SqlService sqlService;

    @Override
    public void execute(JobExecutionContext context)
	    throws JobExecutionException {
    }

}

