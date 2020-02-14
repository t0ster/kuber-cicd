def containerExists(image) {
  return sh(returnStatus: true, script: "docker manifest inspect ${image} > /dev/null") == 0
}

def branchExists(repo, branch) {
  return sh(returnStatus: true, script: "git ls-remote --exit-code --heads git@github.com:t0ster/${repo}.git ${branch}") == 0
}

def cicd(build) {
  def buildNumber = env.BUILD_NUMBER as int
  if (buildNumber > 1) milestone(buildNumber - 1)
  milestone(buildNumber)


  // def build = "ui"
  def branch = null
  if (env.CHANGE_BRANCH) {
    branch = env.CHANGE_BRANCH
  } else {
    branch = env.BRANCH_NAME
  }

  def containers = [
    "ui": [
      "image": "t0ster/kuber-ui",
      "tag": branch
    ],
    "functions": [
      "image": "t0ster/kuber-functions",
      "tag": branch
    ],
    "selenium": [
      "image": "t0ster/kuber-selenium",
      "tag": branch
    ],
  ]

  podTemplate(
    containers: [
            containerTemplate(name: 'jnlp', image: 'jenkins/jnlp-slave'),
            containerTemplate(name: 'builder', image: 't0ster/build-deploy:0.0.2', command: 'cat', ttyEnabled: true, envVars: [
                envVar(key: 'DOCKER_HOST', value: 'tcp://dind:2375'),
                envVar(key: 'DOCKER_CLI_EXPERIMENTAL', value: 'enabled')
            ])
    ]
  ) {
      node(POD_LABEL) {
          stage('Build') {
              container('builder') {
                  containers.each { repo, val ->
                    if (repo != build) {
                      if (!containerExists("${val['image']}:${val['tag']}")) {
                        val['tag'] = 'master'
                      }
                    }
                  }
                  checkout scm
                  sha = sh(returnStdout: true, script: "git log -n 1 --pretty=format:'%h'").trim()
                  // git branch: uiTag, changelog: false, poll: false, url: 'https://github.com/t0ster/kuber-ui.git'
                  docker.withRegistry('', 'dockerhub-registry') {
                    def image = containers[build]['image']
                    def tag = containers[build]['tag']
                    def customImage = docker.build("${image}:${tag}")
                    customImage.push()
                  //   sh "docker rmi t0ster/kuber-functions:master"
                  }
              }
          }
          stage('Deploy') {
              def kuberBranch = branchExists('kuber', branch) ? branch : 'master'
              def namespace = (branch == 'master') ? 'stg' : branch
              def patchOrg = """
                  {
                      "release": "kuber-${branch}",
                      "repo": "https://github.com/t0ster/kuber.git",
                      "branch" : "${kuberBranch}",
                      "path": "charts/kuber-stack",
                      "namespace": "${namespace}",
                      "values": {
                          "host": "${branch}.kuber.35.246.75.225.nip.io",
                          "ui": {
                              "image": {
                                  "tag": "${containers['ui']['tag']}",
                                  "pullPolicy": "Always",
                                  "release": "kuber-${build}-${branch}-${BUILD_ID}"
                              }
                          },
                          "functions": {
                              "image": {
                                  "tag": "${containers['functions']['tag']}",
                                  "pullPolicy": "Always",
                                  "release": "kuber-${build}-${branch}-${BUILD_ID}"

                              }
                          }
                      }
                  }
              """
              echo patchOrg
              def response = httpRequest acceptType: 'APPLICATION_JSON', contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: patchOrg, url: "http://deployer-kuber-deployer.kube-system"
              def jsonObj = readJSON text: response.content
              echo jsonObj['result']
          }
          stage('Functional Test') {
            def image = containers['selenium']['image']
            def tag = containers['selenium']['tag']
            podTemplate(
                    containers: [
                            containerTemplate(name: 'jnlp', image: 'jenkins/jnlp-slave'),
                            containerTemplate(name: 'selenium', alwaysPullImage: true, image: "${image}:${tag}", command: 'cat', ttyEnabled: true, envVars: [
                                envVar(key: 'SELENIUM_HOST', value: 'zalenium'),
                                envVar(key: 'BASE_URL', value: "http://${branch}.kuber.35.246.75.225.nip.io"),
                                envVar(key: 'BUILD', value: "kuber-${build}-${branch}-${BUILD_ID}"),
                            ])
                    ]
            ) {
              node(POD_LABEL) {
                container('selenium') {
                    try {
                        sh 'pytest /app --verbose --junit-xml reports/tests.xml'
                    } finally {
                        junit testResults: 'reports/tests.xml'
                        echo "http://zalenium.35.246.75.225.nip.io/dashboard/?q=build:kuber-${build}-${branch}-${BUILD_ID}"
                    }
                }
              }
            }
        }
    }
  }
}
