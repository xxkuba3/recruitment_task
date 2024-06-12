# Intro
Task is about syncing Jira tickets from one project to another within one Jira instance.
Please create Jira instance (its free) where you can create 2 projects that are going to be synchronized.
https://www.atlassian.com/try/cloud/signup

# Task
1. Fork this repository and send link to it to radek@getint.io after task completion
2. Implement `JiraSynchronizer.moveTasksToOtherProject` method. Search for 5 tickets in one project, and move them to the other project within same Jira instance.
When syncing tickets please include following fields:
- summary (title)
- description
- priority
Bonus points for syncing status and comments.
3. Please complete task within 7 days from the day you received it
  
API endpoints exposed by Jira you can find here:
https://developer.atlassian.com/cloud/jira/software/rest/
