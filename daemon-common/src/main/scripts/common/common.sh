#! /bin/bash

SOURCEDIR=`dirname "${BASH_SOURCE[0]}"`

source "${SOURCEDIR}/colors.sh"

########################################################################
### Logging 

function showError() {
	echo -e "${txtred}[ERROR] $1:${txtrst} $2"
}

function showWarn() {
	echo -e "${txtylw} [WARN]${txtrst} $*"
}

function showInfo() {
	echo -e "${txtwht} [INFO]${txtrst} $*"
}

function showDebug() {
	echo -e "${txtpur}[DEBUG]${txtrst} $*"
}

function showSuccess() {
	echo -e "${txtgrn}Success: ${txtrst} $*"
}

###
# Debug Method: Outputs messages to stderr if "debug mode" is enabled
#
function debug(){ 
	if [[ ! -z ${DEBUG} ]]; then
		showDebug $*
	fi
}


########################################################################
### Process Monitoring
function checkExitCode() {
	EXIT_CODE=$?

	if [[ ${EXIT_CODE} != 0 ]]; then
		ERROR="Process returned non-zero exit code (${EXIT_CODE})"
		if [[ ! -z $1 ]]; then
			ERROR="${ERROR}: ${txtwht}$1${txtrst} $2"
		fi

		showError "${ERROR}"
		exit -999;
	fi
}
