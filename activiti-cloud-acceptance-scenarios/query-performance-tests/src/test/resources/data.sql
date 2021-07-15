-- config
ALTER ROLE alfresco IN DATABASE postgres SET search_path TO apppostgresperformancetest,public;

set session query.process_definitions = '100';
set session query.process_instances = '1000';
set session query.tasks = '1000';
set session query.process_variables = '10000';
set session query.task_variables = '10000';

-- clean up
DELETE FROM task_variable;
DELETE FROM process_model;
DELETE FROM process_variable;
DELETE FROM task;
DELETE FROM process_instance;
DELETE FROM process_definition;
DELETE FROM audit_event;

-- Task Events 
INSERT INTO audit_event(id, event_id, sequence_number, type, event_type, task_id, task_name, task)
SELECT
  seq,
  seq AS event_id,
  0 as sequence_number,
  'TaskCreatedEvent' AS type,
  'TASK_CREATED' AS event_type,
  'task_' || seq AS task_id,
  'Task Name ' || seq AS task_name,
  '{"id":"' || seq || '", "status":"CREATED"}' AS task
FROM GENERATE_SERIES(1, current_setting('query.tasks')::int) seq;

INSERT INTO audit_event(id, event_id, sequence_number, type, event_type, task_id, task_name, task)
SELECT
  seq,
  seq AS event_id,
  0 as sequence_number,
  'TaskCompletedEvent' AS type,
  'TASK_COMPLETED' AS event_type,
  'task_' || seq AS task_id,
  'Task Name ' || seq AS task_name,
  '{"id":"' || seq || '", "status":"COMPLETED"}' AS task
FROM GENERATE_SERIES(current_setting('query.tasks')::int + 1 , current_setting('query.tasks')::int * 2) seq;

select id, event_id, sequence_number, type, event_type, task_id, task_name, task from audit_event LIMIT 100;

-- Process Definitions
INSERT INTO process_definition(id, name, description, process_definition_key, version)
SELECT
  seq,
  'process_definition_' || seq AS name,
  'description process_definition_' || seq AS description,
  'process_definition_key_' || seq AS description,
  (RANDOM() * 2)::INT AS version
FROM GENERATE_SERIES(1, current_setting('query.process_definitions')::int) seq;

-- Process Instances
INSERT INTO process_instance(id, process_definition_id, name, status, start_date, completed_date)
WITH expanded AS (
  SELECT RANDOM(), seq, process_definition_id
  FROM GENERATE_SERIES(1, current_setting('query.process_instances')::int) seq, GENERATE_SERIES(1, current_setting('query.process_definitions')::int) process_definition_id
), shuffled AS (
  SELECT e.*, 
    CASE (RANDOM() * 2)::INT
      WHEN 0 THEN 'RUNNING'
      WHEN 1 THEN 'CREATED'
      WHEN 2 THEN 'COMPLETED'
    END AS status
  FROM expanded e
  INNER JOIN (
    SELECT ei.seq, MIN(ei.random) FROM expanded ei GROUP BY ei.seq
  ) em ON (e.seq = em.seq AND e.random = em.min)
  ORDER BY e.seq
)
SELECT
  s.seq as id,
  s.process_definition_id,
  'process ' || s.seq AS name,
  s.status,
  timestamp '2020-01-10 20:00:00' + (random() * (interval '90 days')) + '30 days' AS start_date,
  NOW() + (random() * (interval '90 days')) + '30 days' AS completed_date
FROM shuffled s;

-- Tasks
INSERT INTO task(id, process_instance_id, name, status, priority, created_date, due_date)
WITH expanded AS (
  SELECT RANDOM(), seq, process_instance_id
  FROM GENERATE_SERIES(1, current_setting('query.tasks')::int) seq, GENERATE_SERIES(1, current_setting('query.process_instances')::int) process_instance_id
), shuffled AS (
  SELECT e.*, 
  	CASE (RANDOM() * 2)::INT
      WHEN 0 THEN 'CREATED'
      WHEN 1 THEN 'ASSIGNED'
      WHEN 2 THEN 'COMPLETED'
  	END AS status
  FROM expanded e
  INNER JOIN (
    SELECT ei.seq, MIN(ei.random) FROM expanded ei GROUP BY ei.seq
  ) em ON (e.seq = em.seq AND e.random = em.min)
  ORDER BY e.seq
)
SELECT
  s.seq as id,
  s.process_instance_id,
  'task ' || s.seq AS name,
  s.status,
  (RANDOM() * 2)::INT AS priority,  
  timestamp '2020-01-10 20:00:00' + (random() * (interval '90 days')) + '30 days' AS created_date,
  NOW() + (random() * (interval '90 days')) + '30 days' AS due_date
FROM shuffled s;

-- Process Variables
INSERT INTO process_variable(id, process_instance_id, name, type, value)
WITH expanded AS (
  SELECT RANDOM(), seq, process_instance_id
  FROM GENERATE_SERIES(1, current_setting('query.process_variables')::int) seq, GENERATE_SERIES(1, current_setting('query.process_instances')::int) process_instance_id
), shuffled AS (
  SELECT e.*, 
	  (CASE (RANDOM() * 2)::INT
      WHEN 0 THEN 'int'
      WHEN 1 THEN 'string'
      WHEN 2 THEN 'boolean'
    END
  ) as type
  FROM expanded e
  INNER JOIN (
    SELECT ei.seq, MIN(ei.random) FROM expanded ei GROUP BY ei.seq
  ) em ON (e.seq = em.seq AND e.random = em.min)
  ORDER BY e.seq
)
SELECT
  s.seq as id,
  s.process_instance_id,
  'proc_var_' || s.seq as name,
  s.type,
  (CASE 
      WHEN s.type = 'int' THEN '{"value":1234}'
      WHEN s.type = 'string' THEN '{"value":"something"}'
      WHEN s.type = 'boolean' THEN '{"value":true}'
    END
  ) as value
FROM shuffled s;

-- Task Variables
INSERT INTO task_variable(id,task_id,name, type, value)
WITH expanded AS (
  SELECT RANDOM(), seq, task_id
  FROM GENERATE_SERIES(1, current_setting('query.task_variables')::int) seq, GENERATE_SERIES(1, current_setting('query.tasks')::int) task_id
), shuffled AS (
  SELECT e.*, 
	  (CASE (RANDOM() * 2)::INT
      WHEN 0 THEN 'int'
      WHEN 1 THEN 'string'
      WHEN 2 THEN 'boolean'
    END
  ) as type
  FROM expanded e
  INNER JOIN (
    SELECT ei.seq, MIN(ei.random) FROM expanded ei GROUP BY ei.seq
  ) em ON (e.seq = em.seq AND e.random = em.min)
  ORDER BY e.seq
)
SELECT
  s.seq as id,
  s.task_id,
  'task_var_' || s.seq as name,
  s.type,
  (CASE 
      WHEN s.type = 'int' THEN '{"value":1234}'
      WHEN s.type = 'string' THEN '{"value":"something"}'
      WHEN s.type = 'boolean' THEN '{"value":true}'
    END
  ) as value
FROM shuffled s;

SELECT id,task_id,name, type, value FROM task_variable LIMIT 100;

