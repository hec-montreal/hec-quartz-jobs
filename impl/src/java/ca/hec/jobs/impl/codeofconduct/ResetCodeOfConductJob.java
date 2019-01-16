package ca.hec.jobs.impl.codeofconduct;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import ca.hec.jobs.impl.AbstractQuartzJobImpl;

public class ResetCodeOfConductJob extends AbstractQuartzJobImpl {

	private static Log log = LogFactory
			.getLog(ResetCodeOfConductJob.class);

	private JdbcTemplate jdbcTemplate;

	public void setDataSource(DataSource dataSource) {
		this.jdbcTemplate = new JdbcTemplate(dataSource);
	}

	@Transactional
	@Override
	public void execute(JobExecutionContext arg0) throws JobExecutionException {
		int nbAcceptedCC = 0;

		log.info("starting ResetCodeOfConductJob");
	
		loginToSakai("ResetCodeOfConductJob");

		// copy code_of_conduct to code_of_conduct_delete
		String backupDataSql = "insert into code_of_conduct_delete select * from code_of_conduct";
		jdbcTemplate.execute(backupDataSql);

		// empty code_of_conduct table
		String emptyTableSql = "delete from code_of_conduct";
		nbAcceptedCC = jdbcTemplate.update(emptyTableSql);
		
    	logoutFromSakai();
    	log.info("Moved " + nbAcceptedCC + " accepted code of conduct. ");

	}

}
