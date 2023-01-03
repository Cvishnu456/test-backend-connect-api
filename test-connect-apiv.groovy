try {
    if ("${PR_STATE}" == "OPEN") {
        TARGET_BRANCH = "${PR_SOURCE_BRANCH}"
        currentBuild.description = "Opened PR #${PR_ID}: ${PR_TITLE}"
        env.STATUS_COMMIT = PR_COMMIT
    } else {
        TARGET_BRANCH = "${PR_DESTINATION_BRANCH}"
        currentBuild.description = "Merged PR #${PR_ID}: ${PR_TITLE}"
        env.STATUS_COMMIT = PR_MERGE_COMMIT
    }
} catch(err) {
    TARGET_BRANCH = "${params.BRANCH}"
    currentBuild.description = "${params.BRANCH}"
    env.PR_STATE = "NO"
}

def map_version_suffix = [
       develop : "-SNAPSHOT",
       feature : "-SNAPSHOT",
       master : "",
       main : "",
       release : "-RC"
]

pipeline {
    options {
        buildDiscarder(
            logRotator(
                artifactDaysToKeepStr: "",
                artifactNumToKeepStr: "",
                daysToKeepStr: "",
                numToKeepStr: "20"
            )
        )
        disableConcurrentBuilds()
    }
    environment{
        TENANT = "axci4wuwpelt"
        projectName = "connect-api"
        SCM_URL="https://QuaraDev99@bitbucket.org/quara-lending/connect-api.git"
        registryHost = "https://me-jeddah-1.ocir.io"
        registryCredentials = "docker_quarapay"
        repository_id = "ocid1.artifactrepository.oc1.me-jeddah-1.0.amaaaaaaj3bncnaaqorp3qitujnjmphbtcxmzjuzbodnspmvesfqlogrtkjq"
        artifact_prefix = "jenkins"
        //TODO: MOVE ENCRYPTOR_KEY & ENCRYPTOR_SALT to Credentials plugin https://quara99.atlassian.net/browse/Q99-722
        ENCRYPTOR_KEY = "XEuZBR.X3UzrDF1="
        ENCRYPTOR_SALT = "8d6f571e8d8b3bd2"
        TOKEN_TTL = 900
        RELEASE_VER = ".${BUILD_NUMBER}"
        REMOTE_JENKINS_URL = "http://192.168.100.46:8080"
        JOB_SUFFIX = "job/TEMPLATE/job/connect-api-deploy/buildWithParameters?token=connect_api_deploy_token"
    }
    agent any
    parameters {
        string(name: 'BRANCH', defaultValue: 'develop', description: 'A branch name for the repository')
    }
    stages {
       stage('Checkout repository'){
            steps{
                script{
                  try{
                    retry(3) {
                      checkout([$class: 'GitSCM',
                      branches: [[name: "*/${TARGET_BRANCH}"]],
                      extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'connect-api']],
                      userRemoteConfigs: [[credentialsId: 'bitbucket-jenkins-app2', url: "${SCM_URL}"]]])

                      if (PR_STATE != "NO"){
                        //env.PR_COMMIT_ID=sh(script: 'cd ${WORKSPACE}/connect-api; git show -s --format=%H ${STATUS_COMMIT}', returnStdout: true).trim()
                        withCredentials([usernamePassword(credentialsId: 'bitbucket-read-only', passwordVariable: 'S_PASS', usernameVariable: 'S_USER')]) {
                          env.COMMIT_URL="https://api.bitbucket.org/2.0/repositories/quara-lending/${projectName}/commit/${STATUS_COMMIT}"
                          env.PR_COMMIT_ID = sh (script: 'curl --user "$S_USER":"$S_PASS" ${COMMIT_URL} | jq -r ".hash"',returnStdout: true).trim()
                          println "${PR_COMMIT_ID}"
                        }
                        bitbucketStatusNotify (
                          buildState: 'INPROGRESS',
                          repoSlug: 'connect-api',
                          commitId: "${PR_COMMIT_ID}"
                        )
                      }
                   }
                  } catch (e) {
                      currentBuild.result = "FAILURE"
                  }
               }
            }
        }
        stage('Maven build'){
            steps{
               script {
                 def pom = new File("${WORKSPACE}/connect-api/pom.xml").getText('utf-8')
                 def doc = new XmlSlurper().parseText(pom)
                 def version = doc.version.text()
                 def pt=0
                 def version_short=""
                 version.each {
                     if (it=='.') {pt=pt+1}
                     if (it=='-') {pt=2}
                     if (pt<2) {version_short=version_short+it}
                 }
                 def branch_short = TARGET_BRANCH
                 if (TARGET_BRANCH.length()>6 && TARGET_BRANCH.substring(1,7)=='eature')
                   {branch_short = 'feature'}
                 if (TARGET_BRANCH.length()>6 && TARGET_BRANCH.substring(1,7)=='elease')
                   {branch_short = 'release'
                    version_short = TARGET_BRANCH.substring(8)
                   }
                 RELEASE_VER = version_short+RELEASE_VER+map_version_suffix["$branch_short"]
               }

               withMaven {  
                 sh """
                   cd ${WORKSPACE}/connect-api
                   export JAVA_HOME=/opt/jdk-17
                   export PATH=\$JAVA_HOME/bin:\$PATH
                   export ENCRYPTOR_KEY=${ENCRYPTOR_KEY}
                   export ENCRYPTOR_SALT=${ENCRYPTOR_SALT}
                   export TOKEN_TTL=${TOKEN_TTL}
                   mvn versions:set -DnewVersion=${RELEASE_VER}
                   mvn clean compile package install
                 """
              }
            }
        }
        stage('SonarQube analysis') {
            environment {
              SCANNER_HOME = tool 'Sonar-scanner'
            }
            steps {
            withSonarQubeEnv(credentialsId: 'sonarcloudnew', installationName: 'Sonar') {
                 sh """
                  export JAVA_HOME=/opt/jdk-17
                  cd ${WORKSPACE}/connect-api
                  $SCANNER_HOME/bin/sonar-scanner \
                 -Dsonar.organization=quara-lending \
                 -Dsonar.projectKey=quara-lending_${projectName} \
                 -Dsonar.sources=\$(find . -path '*/src/main' | xargs | tr ' ' ',') \
                 -Dsonar.java.binaries=. \
                 -Dsonar.java.libraries=\$(find ~/.m2  -path '*quarapay*.jar'  -type f -exec stat -c '%X %n' {} \\; | sort -nr | awk 'NR==1,NR==1000 {print \$2}' | xargs | tr ' ' ',') \
                 -Dsonar.projectVersion=${RELEASE_VER} \
                 -Dsonar.branch.name=${TARGET_BRANCH}
                 """
               }
             }
        }
        stage('SQuality Gate') {
          steps {
              timeout(time: 5, unit: 'MINUTES') {
                script {
                   catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                     try {
                       waitForQualityGate abortPipeline: true
                     }
                     catch (Exception e) {
                          println "SonarCloud Quality Gates failed"
                          throw e
                     }
                  }
                }
             }
          }
        }
        stage('Artifact upload'){
            when {
               expression {PR_STATE == "MERGED"}
            }
            steps{
                sh """
                   cd ${WORKSPACE}/connect-api
                   export JAVA_HOME=/opt/jdk-17
                   export PATH=\$JAVA_HOME/bin:\$PATH
                   mvn deploy -Dmaven.test.skip=true
                 """
            }
        }
        stage('Docker image build'){
            when {
               expression {PR_STATE != "OPEN" || TARGET_BRANCH == "main" || TARGET_BRANCH == "master" || TARGET_BRANCH.length()>6 && TARGET_BRANCH.substring(1,7)=='elease'}
            }
            steps{
               script {
                 dockerImage = docker.build("${TENANT}/${projectName}:${RELEASE_VER}", "--build-arg JAR_FILE=connect-api/connect/target/connect-${RELEASE_VER}.jar -f ${WORKSPACE}/CI_CD/backend/connect-api/Dockerfile .")
               }
            }
        }
        stage("Deploy image to registry"){
            when {
               expression {PR_STATE != "OPEN" || TARGET_BRANCH == "main" || TARGET_BRANCH == "master" || TARGET_BRANCH.length()>6 && TARGET_BRANCH.substring(1,7)=='elease'}
            }
            steps{
                script{
                    docker.withRegistry(registryHost,registryCredentials)
                        dockerImage.push()
                        dockerImage.push('latest')
                    }
                }
            }
        }
        stage("Remove unused docker image"){
            when {
               expression {PR_STATE != "OPEN" || TARGET_BRANCH == "main" || TARGET_BRANCH == "master" || TARGET_BRANCH.length()>6 && TARGET_BRANCH.substring(1,7)=='elease'}
            }
            steps{
                sh """
                  docker rmi -f ${projectName}:${RELEASE_VER} >/dev/null 2>&1  || true
                  docker rmi -f ${projectName}:${RELEASE_VER} >/dev/null 2>&1  || true
                """
            }
        }
        stage("Deploy: Merged PR Only") {
            when {
                expression {
                  TARGET_BRANCH =='develop' && PR_STATE == "MERGED" ||
                  TARGET_BRANCH.length()>6 && TARGET_BRANCH.substring(1,7)=='elease' && PR_STATE != "NO" ||
                  TARGET_BRANCH =='master' && PR_STATE != "NO" ||
                  TARGET_BRANCH =='main' && PR_STATE != "NO"
                }
            }
            steps {
                script {
                   if (TARGET_BRANCH =='develop' && PR_STATE == "MERGED" || TARGET_BRANCH.length()>6 && TARGET_BRANCH.substring(1,7)=='elease' && PR_STATE == "OPEN"){
                     env.ENV_TO_DEPLOY = 'dev'
                     env.UPDATE_DB = false
                   }
                   else if (TARGET_BRANCH.length()>6 && TARGET_BRANCH.substring(1,7)=='elease' && PR_STATE == "MERGED" || TARGET_BRANCH =='master' && PR_STATE == "OPEN" || TARGET_BRANCH =='main' && PR_STATE == "OPEN") {
                     env.ENV_TO_DEPLOY = 'test'
                     env.UPDATE_DB = true
                   }
                   else { TARGET_BRANCH =='master' && PR_STATE == "MERGED" || TARGET_BRANCH =='main' && PR_STATE == "MERGED"
                     env.ENV_TO_DEPLOY = 'uat'
                     env.UPDATE_DB = true
                   }

                   if (ENV_TO_DEPLOY == 'dev' || ENV_TO_DEPLOY == 'test') {
                      build job: 'TEMPLATE/connect-api-deploy', parameters: [string(name: 'VERSION', value: "${RELEASE_VER}"), string(name: 'ENVIRONMENT', value: "${ENV_TO_DEPLOY}"), booleanParam(name: 'UPDATE_DB', value: UPDATE_DB), string(name: 'TARGET_BRANCH', value: "${TARGET_BRANCH}")]
                   }
                   else {
                      withCredentials([usernamePassword(credentialsId: 'private_jenkins_token', passwordVariable: 'S_PASS', usernameVariable: 'S_USER')]) {
                        env.DATA_PARAMS = "--data VERSION=${RELEASE_VER} --data ENVIRONMENT=${ENV_TO_DEPLOY} --data UPDATE_DB=${UPDATE_DB} --data TARGET_BRANCH=${TARGET_BRANCH}"

                        // call remote Jenkins job, get a queue URL
                        env.QUEUE_URL = sh (script: 'curl -i -X POST --user "$S_USER":"$S_PASS" $REMOTE_JENKINS_URL/$JOB_SUFFIX $DATA_PARAMS | grep queue | awk "{print \\$2}"',returnStdout: true).trim()
                        env.QUEUE_URL = "${QUEUE_URL}api/json"
                        println "${QUEUE_URL}"

                        // call the queue URL, get a job URL
                        sleep 10
                        env.JOB_URL = sh (script: 'curl --silent --user "$S_USER":"$S_PASS" $QUEUE_URL | jq ".executable.url"',returnStdout: true).trim()
                        println "${JOB_URL}"
                        env.JOB_URL = JOB_URL.substring(1,JOB_URL.length()-1)
                        env.IS_BUILDING = 'true'
                        int_count = 0
                        // call the job URL, get its' status
                        // while the job status is 'building', wait
            						env.JOB_CONSOLE_URL = "${JOB_URL}consoleText"
						            env.JOB_URL = "${JOB_URL}api/json"
                        while (env.IS_BUILDING == 'true' && int_count<30) {
                             int_count++
                             sleep 10
                             env.IS_BUILDING = sh (script: 'curl --silent --user "$S_USER":"$S_PASS" $JOB_URL | jq ".building"',returnStdout: true).trim()
                             println "is building: ${IS_BUILDING}"
                        }
                        // log to this console the remote console log and get a result
                        sh """
						              curl --silent --user "$S_USER":"$S_PASS" $JOB_CONSOLE_URL
						            """
						            env.JOB_RESULT = sh (script: 'curl --silent --user "$S_USER":"$S_PASS" $JOB_URL | jq ".result"',returnStdout: true).trim()
                        // if the result is not SUCCESS, fail this pipeline
                        if (JOB_RESULT !='"SUCCESS"') {
                          currentBuild.result = "FAILURE"
                          throw new Exception()
                        }
                     }
                   }
                }
            }
        }
    }
    post {
        always {
            cleanWs()
        }
        success {
            script{
              if (PR_STATE != "NO"){
                bitbucketStatusNotify (
                  buildState: 'SUCCESSFUL',
                  repoSlug: 'connect-api',
                  commitId: "${PR_COMMIT_ID}"
                )
              }
            }
        }
        failure {
            script {
              if (PR_STATE != "NO"){
                bitbucketStatusNotify (
                  buildState: 'FAILED',
                  repoSlug: 'connect-api',
                  commitId: "${PR_COMMIT_ID}"
                )
              }
            }
        }
    }
}