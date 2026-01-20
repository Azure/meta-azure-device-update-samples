#!/bin/bash

# Copyright (c) Microsoft Corporation.
# Licensed under the MIT License.

# ADU Script Handler for Delta Source Caching
# Purpose: Installs updates and caches recompressed SWU as delta source
# Based on: yocto-a-b-update.sh (simplified)

set -e

# Ensure that getopt starts from first option if ". <script.sh>" was used.
OPTIND=1

ret_val=1

# Ensure we dont end the user's terminal session if invoked from source (".").
if [[ $0 != "${BASH_SOURCE[0]}" ]]; then
    ret='return'
else
    ret='exit'
fi

#
# Default file paths
#
output_file=/adu/logs/delta-source-caching.output
log_file=/adu/logs/delta-source-caching.log
result_file=/adu/logs/delta-source-caching.result.json
software_version_file="/etc/adu-version"

# Delta cache configuration
DELTA_CACHE_DIR="/adu/data/delta-cache"

#
# Action flags
#
check_is_installed=
do_download_action=
do_install_action=
do_apply_action=

#
# Parameters
#
image_file=""
installed_criteria=""
workflow_id=""

#
# Output helper functions
#
_timestamp=

update_timestamp() {
    _timestamp="$(date +'%Y/%m/%d:%H%M%S')"
}

log() {
    update_timestamp
    if [ -z "$log_file" ]; then
        echo -e "[$_timestamp]" "$@" >&1
    else
        echo "[$_timestamp]" "$@" >> "$log_file"
    fi
}

output() {
    update_timestamp
    if [ -z "$output_file" ]; then
        echo "[$_timestamp]" "$@" >&1
    else
        echo "[$_timestamp]" "$@" >> "$output_file"
    fi
}

result() {
    # NOTE: don't insert timestamp in result file.
    if [ -z "$result_file" ]; then
        echo "$@" >&1
    else
        echo "$@" > "$result_file"
    fi
}

#
# Helper to create extended result codes
# Usage: make_erc error_value result_variable
#
make_erc() {
    local base_erc=0x30102000  # Different from yocto-a-b-update to avoid conflicts
    local -n res=$2
    res=$((base_erc + $1))
}

#
# Helper to create ADUC result JSON
# Usage: make_aduc_result_json $resultCode $extendedResultCode $resultDetails <out param>
#
make_aduc_result_json() {
    local -n res=$4
    res="{\"resultCode\":$1, \"extendedResultCode\":$2,\"resultDetails\":\"$3\"}"
}

#
# Action: Check if version is installed
# Returns: 0 if installed, 1 if not installed
#
action_is_installed() {
    log "ACTION: is-installed"
    log "  Checking if version '$installed_criteria' is installed..."
    
    if [ ! -f "$software_version_file" ]; then
        log "  Version file not found: $software_version_file"
        echo "900"  # Not installed
        return 0
    fi
    
    current_version=$(cat "$software_version_file" 2>/dev/null || echo "")
    
    if [ "$current_version" = "$installed_criteria" ]; then
        log "  ✓ Version $installed_criteria is installed"
        echo "0"  # Installed
    else
        log "  Current version: $current_version (expected: $installed_criteria)"
        echo "900"  # Not installed
    fi
    
    return 0
}

#
# Action: Download (verification only - DO handles actual download)
# Returns: 0 on success, non-zero on failure
#
action_download() {
    log "ACTION: download"
    log "  Verifying downloaded file: $image_file"
    
    if [ ! -f "$image_file" ]; then
        log "ERROR: Image file not found: $image_file"
        
        local resultCode=0
        local extendedResultCode=0
        local resultDetails="Download verification failed: image file not found at $image_file"
        local aduc_result=""
        
        make_erc 1 extendedResultCode
        make_aduc_result_json "$resultCode" "$extendedResultCode" "$resultDetails" aduc_result
        
        output "Result:" "$aduc_result"
        result "$aduc_result"
        return 1
    fi
    
    log "  ✓ File exists: $image_file"
    
    # Success result
    local resultCode=0
    local extendedResultCode=0
    local resultDetails="Download verification successful"
    local aduc_result=""
    
    make_aduc_result_json "$resultCode" "$extendedResultCode" "$resultDetails" aduc_result
    
    output "Result:" "$aduc_result"
    result "$aduc_result"
    return 0
}

#
# Helper: Cache recompressed SWU for delta updates
# Returns: 0 on success, non-zero on failure
#
cache_recompressed_swu() {
    local swu_file="$1"
    local recompressed="${swu_file%.swu}-recompressed.swu"
    local cached_file="$DELTA_CACHE_DIR/$(basename "$recompressed")"
    
    log "  Checking for recompressed SWU: $recompressed"
    
    if [ ! -f "$recompressed" ]; then
        log "  ERROR: Recompressed SWU not found: $recompressed"
        log "  Delta source caching is REQUIRED for this handler"
        return 1
    fi
    
    log "  Creating delta cache directory: $DELTA_CACHE_DIR"
    mkdir -p "$DELTA_CACHE_DIR" || {
        log "  ERROR: Failed to create delta cache directory"
        return 1
    }
    
    log "  Copying recompressed SWU to delta cache..."
    cp "$recompressed" "$DELTA_CACHE_DIR/" || {
        log "  ERROR: Failed to copy recompressed SWU to cache"
        return 1
    }
    
    # Verify the file was cached successfully
    if [ ! -f "$cached_file" ]; then
        log "  ERROR: Verification failed - cached file not found: $cached_file"
        return 1
    fi
    
    log "  ✓ Verified cached source: $(basename "$cached_file")"
    return 0
}

#
# Action: Install the update
# Returns: 0 on success, non-zero on failure
#
action_install() {
    log "ACTION: install"
    log "  Installing update from: $image_file"
    
    if [ ! -f "$image_file" ]; then
        log "ERROR: Image file not found: $image_file"
        
        local resultCode=0
        local extendedResultCode=0
        local resultDetails="Install failed: image file not found at $image_file"
        local aduc_result=""
        
        make_erc 2 extendedResultCode
        make_aduc_result_json "$resultCode" "$extendedResultCode" "$resultDetails" aduc_result
        
        output "Result:" "$aduc_result"
        result "$aduc_result"
        return 1
    fi
    
    # Execute SWUpdate
    log "  Executing SWUpdate..."
    log "  Command: swupdate -i $image_file -e stable,copy1"
    
    if swupdate -i "$image_file" -e "stable,copy1" >> "$log_file" 2>&1; then
        log "  ✓ SWUpdate completed successfully"
    else
        local swupdate_ret=$?
        log "ERROR: SWUpdate failed with exit code: $swupdate_ret"
        
        local resultCode=0
        local extendedResultCode=0
        local resultDetails="SWUpdate execution failed (exit code: $swupdate_ret)"
        local aduc_result=""
        
        make_erc 3 extendedResultCode
        make_aduc_result_json "$resultCode" "$extendedResultCode" "$resultDetails" aduc_result
        
        output "Result:" "$aduc_result"
        result "$aduc_result"
        return 1
    fi
    
    # Cache recompressed SWU for delta updates (REQUIRED)
    log "  Caching recompressed SWU as delta source..."
    if ! cache_recompressed_swu "$image_file"; then
        log "ERROR: Failed to cache delta source file"
        
        local resultCode=0
        local extendedResultCode=0
        local resultDetails="Install completed but delta source caching failed"
        local aduc_result=""
        
        make_erc 4 extendedResultCode
        make_aduc_result_json "$resultCode" "$extendedResultCode" "$resultDetails" aduc_result
        
        output "Result:" "$aduc_result"
        result "$aduc_result"
        return 1
    fi
    
    log "  ✓ Delta source cached and verified"
    
    # Success result
    local resultCode=0
    local extendedResultCode=0
    local resultDetails="Install successful"
    local aduc_result=""
    
    make_aduc_result_json "$resultCode" "$extendedResultCode" "$resultDetails" aduc_result
    
    output "Result:" "$aduc_result"
    result "$aduc_result"
    return 0
}

#
# Action: Apply the update (reboot)
# Returns: 0 (doesn't return - system reboots)
#
action_apply() {
    log "ACTION: apply"
    log "  Rebooting system to apply update..."
    
    # Success result before reboot
    local resultCode=0
    local extendedResultCode=0
    local resultDetails="Rebooting to apply update"
    local aduc_result=""
    
    make_aduc_result_json "$resultCode" "$extendedResultCode" "$resultDetails" aduc_result
    
    output "Result:" "$aduc_result"
    result "$aduc_result"
    
    sync
    sleep 2
    reboot
    
    # Should not reach here
    return 0
}

#
# Parse command line arguments
#
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --action)
                action="$2"
                shift 2
                ;;
            --is-installed)
                check_is_installed=1
                installed_criteria="$2"
                shift 2
                ;;
            --download)
                do_download_action=1
                shift
                ;;
            --install)
                do_install_action=1
                shift
                ;;
            --apply)
                do_apply_action=1
                shift
                ;;
            --image-file)
                image_file="$2"
                shift 2
                ;;
            --installed-criteria)
                installed_criteria="$2"
                shift 2
                ;;
            --result-file)
                result_file="$2"
                shift 2
                ;;
            --output-file)
                output_file="$2"
                shift 2
                ;;
            --log-file)
                log_file="$2"
                shift 2
                ;;
            --work-folder)
                # Ignored for compatibility
                shift 2
                ;;
            --workflow-id)
                workflow_id="$2"
                shift 2
                ;;
            *)
                log "WARNING: Unknown argument: $1"
                shift
                ;;
        esac
    done
    
    # Validation
    if [ -z "$result_file" ]; then
        echo "ERROR: --result-file is required" >&2
        return 1
    fi
    
    return 0
}

#
# Main execution
#
main() {
    log "======================================"
    log "ADU Delta Source Caching Handler"
    log "======================================"
    log "Arguments: $@"
    
    # Parse arguments
    if ! parse_args "$@"; then
        return 1
    fi
    
    # Execute action
    if [ -n "$check_is_installed" ]; then
        action_is_installed
        ret_val=$?
    elif [ -n "$do_download_action" ]; then
        action_download
        ret_val=$?
    elif [ -n "$do_install_action" ]; then
        action_install
        ret_val=$?
    elif [ -n "$do_apply_action" ]; then
        action_apply
        ret_val=$?
    else
        log "ERROR: No action specified"
        
        local resultCode=0
        local extendedResultCode=0
        local resultDetails="No action specified"
        local aduc_result=""
        
        make_erc 99 extendedResultCode
        make_aduc_result_json "$resultCode" "$extendedResultCode" "$resultDetails" aduc_result
        
        output "Result:" "$aduc_result"
        result "$aduc_result"
        ret_val=1
    fi
    
    return $ret_val
}

# Execute main
main "$@"
$ret $?
