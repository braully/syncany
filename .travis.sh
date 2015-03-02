#!/bin/bash

set -e

# Run JUnit tests and generate reports
sonarRunnerTask=$([ -n "$TRAVIS_PULL_REQUEST" -a "$TRAVIS_PULL_REQUEST" != "false" ] && echo "" || echo "sonarRunner")
./gradlew testGlobal coberturaReport performCoverageCheck javadocAll $sonarRunnerTask

# Line of code stats
cloc --quiet --xml --out=build/reports/cloc.xml $(find -type d -name main | grep src/main)
  
# Generate manpages
./gradlew manpages

# Create distributables
./gradlew distTar
./gradlew distZip
./gradlew debian
./gradlew exe
    
# Upload distributables and JavaDoc
./gradle/lftp/lftpupload.sh



  # Run JUnit tests and generate reports
  - ./gradlew testGlobal coberturaReport performCoverageCheck javadocAll `./gradle/sonarqube/sonarqube.sh`

after_success:
  # Line of code stats
  - cloc --quiet --xml --out=build/reports/cloc.xml $(find -type d -name main | grep src/main)
  
  # Generate manpages
  - ./gradlew manpages

  # Create distributables
  - ./gradlew distTar
  - ./gradlew distZip
  - ./gradlew debian
  - ./gradlew exe
    
  # Upload distributables and JavaDoc
  - ./gradle/lftp/lftpupload.sh

