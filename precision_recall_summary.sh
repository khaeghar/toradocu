#!/bin/sh

# Fail if any command fails
set -e

# Stats file used is the default for Toradocu
STATS_FILE=results.csv

if [ -f $STATS_FILE ]; then
    rm -i $STATS_FILE # Remove old stats file (if user agrees)
fi

if [ ! -f $STATS_FILE ]; then
    echo "METHOD,CORRECT CONDITIONS,WRONG CONDITIONS,MISSING CONDITIONS" > $STATS_FILE
fi

# Run Toradocu and collect statistics
./gradlew clean test --tests "org.toradocu.PrecisionRecallTestSuite" --rerun-tasks
echo "TOTAL,=SUM(B2:INDIRECT(\"B\" & ROW()-1)),=SUM(C2:INDIRECT(\"C\" & ROW()-1)),=SUM(D2:INDIRECT(\"D\" & ROW()-1))" >> $STATS_FILE
echo "NUMBER OF METHODS,=ROW()-3" >> $STATS_FILE
echo "NUMBER OF CONDITIONS,=INDIRECT(\"B\" & ROW()-2)+INDIRECT(\"C\" & ROW()-2)+INDIRECT(\"D\" & ROW()-2)" >> $STATS_FILE
echo "PRECISION,=INDIRECT(\"B\" & ROW()-3)/(INDIRECT(\"B\" & ROW()-3) + INDIRECT(\"C\" & ROW()-3))" >> $STATS_FILE
echo "RECALL,=INDIRECT(\"B\" & ROW()-4)/INDIRECT(\"B\" & ROW()-2)" >> $STATS_FILE

echo "Open the result file: $STATS_FILE"