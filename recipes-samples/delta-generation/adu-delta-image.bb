# Generates delta/diff files between ADU update images
# This recipe creates binary diffs for testing delta updates
# Uses Azure IoT Hub Device Update DiffGenTool for delta generation
# Supports: v1->v2, v2->v3, and v1->v3 delta generation

DESCRIPTION = "ADU Delta Update File Generator (v1->v2, v2->v3, v1->v3)"
LICENSE = "CLOSED"

# No SRC_URI needed - signing is done directly with openssl in bash function

# Inherit timestamp validation to ensure deltas are rebuilt when base image changes
# Only enabled when delta update feature is turned on (WITH_FEATURE_DELTA_UPDATE='1')
# This prevents accidentally deploying delta files created from stale update packages
inherit ${@bb.utils.contains('WITH_FEATURE_DELTA_UPDATE', '1', 'adu-timestamp-check', '', d)}

# Remove old deployed delta files in a separate task before deploy
# This is necessary because cleansstate doesn't clean the deploy directory
python do_clean_old_deploys() {
    import glob
    import os
    
    deploy_dir = d.getVar('DEPLOY_DIR_IMAGE')
    
    # Remove old delta files and manifests
    old_diff_pattern = os.path.join(deploy_dir, 'adu-delta*.diff')
    old_manifest_pattern = os.path.join(deploy_dir, 'manifest-*-adu-delta-image*.deploy')
    
    for pattern in [old_diff_pattern, old_manifest_pattern]:
        for old_file in glob.glob(pattern):
            bb.note("Removing old deployed delta file: %s" % old_file)
            try:
                os.remove(old_file)
            except OSError as e:
                bb.warn("Could not remove %s: %s" % (old_file, e))
}

addtask clean_old_deploys before do_deploy after do_generate_all_deltas

# Depend on all versioned images and native delta generation tools
DEPENDS = "adu-update-image-v1 adu-update-image-v2 adu-update-image-v3 iot-hub-device-update-delta-diffgentool-native iot-hub-device-update-delta-processor-native"

# Task-level dependencies for all delta generation tasks
# Depend on do_swuimage to ensure SWU files are built and deployed
# AND on diffgentool-native and processor-native to ensure native tools are available
# Serialize delta generation to avoid file conflicts (v1 source accessed by multiple tasks)
do_generate_delta_v1_v2[depends] = "\
    adu-update-image-v1:do_swuimage \
    adu-update-image-v2:do_swuimage \
    iot-hub-device-update-delta-diffgentool-native:do_populate_sysroot \
    iot-hub-device-update-delta-processor-native:do_populate_sysroot \
"

# Run v1→v3 after v1→v2 completes (both access v1 source file)
do_generate_delta_v1_v3[depends] = "\
    adu-update-image-v1:do_swuimage \
    adu-update-image-v3:do_swuimage \
    iot-hub-device-update-delta-diffgentool-native:do_populate_sysroot \
    iot-hub-device-update-delta-processor-native:do_populate_sysroot \
    adu-delta-image:do_generate_delta_v1_v2 \
"

# Run v2→v3 last (after both v1-based deltas complete)
do_generate_delta_v2_v3[depends] = "\
    adu-update-image-v2:do_swuimage \
    adu-update-image-v3:do_swuimage \
    iot-hub-device-update-delta-diffgentool-native:do_populate_sysroot \
    iot-hub-device-update-delta-processor-native:do_populate_sysroot \
    adu-delta-image:do_generate_delta_v1_v3 \
"

# Force re-evaluation of delta generation tasks (no stamp file)
# This ensures BitBake checks dependencies even if task ran before
do_generate_delta_v1_v2[nostamp] = "1"
do_generate_delta_v2_v3[nostamp] = "1"
do_generate_delta_v1_v3[nostamp] = "1"

# No SRC_URI needed - using native diffgentool binary

# Native tools needed during build (host machine)
# iot-hub-device-update-delta-diffgentool-native provides:
#   - diffgentool binary (C# .NET executable) for creating PAMZ format diffs
#   This uses Microsoft's official C# DiffGenTool implementation
DEPENDS += "iot-hub-device-update-delta-diffgentool-native cpio-native"

# Runtime dependencies for target image (no Python or wrapper scripts needed)
RDEPENDS:${PN} += "cpio"

# No install needed - diffgentool comes from native recipe
do_install() {
    # Empty - C# diffgentool is provided by iot-hub-device-update-delta-diffgentool-native
    :
}

# =============================================================================
# RECOMPRESSION AND SIGNING TASKS
# =============================================================================
# These tasks create recompressed+signed SWU files for each version (v1, v2, v3)
# By creating them in separate tasks, we avoid race conditions where multiple
# delta generation tasks try to recompress the same file simultaneously
# =============================================================================

python do_recompress_and_sign_v1() {
    bb.note("=" * 70)
    bb.note("Recompressing and signing v1 SWU file")
    bb.note("=" * 70)
    
    deploy_dir = d.getVar('DEPLOY_DIR_IMAGE')
    delta_output_dir = d.getVar('DELTA_OUTPUT_DIR')
    
    import glob
    import subprocess
    
    # Find v1 SWU file
    swu_files = glob.glob(f"{deploy_dir}/adu-update-image-v1-*.swu")
    if not swu_files:
        bb.fatal("No v1 SWU file found in %s" % deploy_dir)
    
    source_swu = swu_files[0]
    bb.note(f"Found v1 SWU: {source_swu}")
    
    # Create output directory
    os.makedirs(delta_output_dir, exist_ok=True)
    
    unsigned_swu = f"{delta_output_dir}/adu-update-image-v1.0.0-recompressed-unsigned.swu"
    signed_swu = f"{delta_output_dir}/adu-update-image-v1.0.0-recompressed.swu"
    
    # Step 1: Recompress
    bb.note("Step 1: Recompressing v1 SWU...")
    bb.note(f"  Input:  {source_swu}")
    bb.note(f"  Output: {unsigned_swu}")
    
    result = subprocess.run(['recompress', 'swu', source_swu, unsigned_swu],
                          capture_output=True, text=True)
    if result.returncode != 0:
        bb.fatal(f"Recompression failed: {result.stderr}")
    
    # Step 2: Sign (call bash function from BitBake Python)
    bb.note("Step 2: Signing recompressed v1 SWU...")
    bb.note(f"  Input:  {unsigned_swu}")
    bb.note(f"  Output: {signed_swu}")
    
    # Call sign_recompressed_swu bash function
    bb.build.exec_func('sign_recompressed_swu_v1_wrapper', d)
}

# Wrapper bash function to call sign_recompressed_swu for v1
sign_recompressed_swu_v1_wrapper() {
    sign_recompressed_swu \
        "${DELTA_OUTPUT_DIR}/adu-update-image-v1.0.0-recompressed-unsigned.swu" \
        "${DELTA_OUTPUT_DIR}/adu-update-image-v1.0.0-recompressed.swu"
}

python do_recompress_and_sign_v2() {
    bb.note("=" * 70)
    bb.note("Recompressing and signing v2 SWU file")
    bb.note("=" * 70)
    
    deploy_dir = d.getVar('DEPLOY_DIR_IMAGE')
    delta_output_dir = d.getVar('DELTA_OUTPUT_DIR')
    
    import glob
    import subprocess
    
    # Find v2 SWU file
    swu_files = glob.glob(f"{deploy_dir}/adu-update-image-v2-*.swu")
    if not swu_files:
        bb.fatal("No v2 SWU file found in %s" % deploy_dir)
    
    source_swu = swu_files[0]
    bb.note(f"Found v2 SWU: {source_swu}")
    
    # Create output directory
    os.makedirs(delta_output_dir, exist_ok=True)
    
    unsigned_swu = f"{delta_output_dir}/adu-update-image-v2.0.0-recompressed-unsigned.swu"
    signed_swu = f"{delta_output_dir}/adu-update-image-v2.0.0-recompressed.swu"
    
    # Step 1: Recompress
    bb.note("Step 1: Recompressing v2 SWU...")
    bb.note(f"  Input:  {source_swu}")
    bb.note(f"  Output: {unsigned_swu}")
    
    result = subprocess.run(['recompress', 'swu', source_swu, unsigned_swu],
                          capture_output=True, text=True)
    if result.returncode != 0:
        bb.fatal(f"Recompression failed: {result.stderr}")
    
    # Step 2: Sign
    bb.note("Step 2: Signing recompressed v2 SWU...")
    bb.note(f"  Input:  {unsigned_swu}")
    bb.note(f"  Output: {signed_swu}")
    
    bb.build.exec_func('sign_recompressed_swu_v2_wrapper', d)
}

sign_recompressed_swu_v2_wrapper() {
    sign_recompressed_swu \
        "${DELTA_OUTPUT_DIR}/adu-update-image-v2.0.0-recompressed-unsigned.swu" \
        "${DELTA_OUTPUT_DIR}/adu-update-image-v2.0.0-recompressed.swu"
}

python do_recompress_and_sign_v3() {
    bb.note("=" * 70)
    bb.note("Recompressing and signing v3 SWU file")
    bb.note("=" * 70)
    
    deploy_dir = d.getVar('DEPLOY_DIR_IMAGE')
    delta_output_dir = d.getVar('DELTA_OUTPUT_DIR')
    
    import glob
    import subprocess
    
    # Find v3 SWU file
    swu_files = glob.glob(f"{deploy_dir}/adu-update-image-v3-*.swu")
    if not swu_files:
        bb.fatal("No v3 SWU file found in %s" % deploy_dir)
    
    source_swu = swu_files[0]
    bb.note(f"Found v3 SWU: {source_swu}")
    
    # Create output directory
    os.makedirs(delta_output_dir, exist_ok=True)
    
    unsigned_swu = f"{delta_output_dir}/adu-update-image-v3.0.0-recompressed-unsigned.swu"
    signed_swu = f"{delta_output_dir}/adu-update-image-v3.0.0-recompressed.swu"
    
    # Step 1: Recompress
    bb.note("Step 1: Recompressing v3 SWU...")
    bb.note(f"  Input:  {source_swu}")
    bb.note(f"  Output: {unsigned_swu}")
    
    result = subprocess.run(['recompress', 'swu', source_swu, unsigned_swu],
                          capture_output=True, text=True)
    if result.returncode != 0:
        bb.fatal(f"Recompression failed: {result.stderr}")
    
    # Step 2: Sign
    bb.note("Step 2: Signing recompressed v3 SWU...")
    bb.note(f"  Input:  {unsigned_swu}")
    bb.note(f"  Output: {signed_swu}")
    
    bb.build.exec_func('sign_recompressed_swu_v3_wrapper', d)
}

sign_recompressed_swu_v3_wrapper() {
    sign_recompressed_swu \
        "${DELTA_OUTPUT_DIR}/adu-update-image-v3.0.0-recompressed-unsigned.swu" \
        "${DELTA_OUTPUT_DIR}/adu-update-image-v3.0.0-recompressed.swu"
}

addtask recompress_and_sign_v1 after do_unpack before do_generate_delta_v1_v2
addtask recompress_and_sign_v2 after do_unpack before do_generate_delta_v1_v2  
addtask recompress_and_sign_v3 after do_unpack before do_generate_delta_v2_v3

DELTA_OUTPUT_DIR = "${WORKDIR}/delta-output"
DELTA_FILE_NAME_V1_V2 = "adu-delta-v1-to-v2.diff"
DELTA_FILE_NAME_V2_V3 = "adu-delta-v2-to-v3.diff"
DELTA_FILE_NAME_V1_V3 = "adu-delta-v1-to-v3.diff"
RECOMPRESSED_FILE_V1 = "adu-update-image-v1-recompressed.swu"
RECOMPRESSED_FILE_V2 = "adu-update-image-v2-recompressed.swu"
RECOMPRESSED_FILE_V3 = "adu-update-image-v3-recompressed.swu"
DEPLOYDIR = "${DEPLOY_DIR_IMAGE}"

# Export signing key paths to task environment
# These are needed by the signing function
do_recompress_and_sign_v1[vardeps] += "ADUC_PRIVATE_KEY ADUC_PRIVATE_KEY_PASSWORD"
do_recompress_and_sign_v2[vardeps] += "ADUC_PRIVATE_KEY ADUC_PRIVATE_KEY_PASSWORD"
do_recompress_and_sign_v3[vardeps] += "ADUC_PRIVATE_KEY ADUC_PRIVATE_KEY_PASSWORD"

# Task dependencies: Recompression tasks depend on SWU files and native tools
do_recompress_and_sign_v1[depends] = "\
    adu-update-image-v1:do_swuimage \
    iot-hub-device-update-delta-processor-native:do_populate_sysroot \
"

do_recompress_and_sign_v2[depends] = "\
    adu-update-image-v2:do_swuimage \
    iot-hub-device-update-delta-processor-native:do_populate_sysroot \
"

do_recompress_and_sign_v3[depends] = "\
    adu-update-image-v3:do_swuimage \
    iot-hub-device-update-delta-processor-native:do_populate_sysroot \
"

# Delta generation tasks depend on recompressed files (not original SWUs)
# This ensures v1 is only recompressed once, avoiding race conditions
do_generate_delta_v1_v2[depends] = "\
    adu-delta-image:do_recompress_and_sign_v1 \
    adu-delta-image:do_recompress_and_sign_v2 \
    iot-hub-device-update-delta-diffgentool-native:do_populate_sysroot \
"

do_generate_delta_v2_v3[depends] = "\
    adu-delta-image:do_recompress_and_sign_v2 \
    adu-delta-image:do_recompress_and_sign_v3 \
    iot-hub-device-update-delta-diffgentool-native:do_populate_sysroot \
"

do_generate_delta_v1_v3[depends] = "\
    adu-delta-image:do_recompress_and_sign_v1 \
    adu-delta-image:do_recompress_and_sign_v3 \
    iot-hub-device-update-delta-diffgentool-native:do_populate_sysroot \
"

# Helper function to sign a recompressed SWU file
# This function mimics the signing logic from swupdate-common.bbclass
# It extracts the SWU, signs sw-description with openssl, and recreates the archive
sign_recompressed_swu() {
    local input_swu="$1"
    local output_swu="$2"
    
    if [ ! -f "$input_swu" ]; then
        bbfatal "Input SWU file not found: $input_swu"
    fi
    
    # Create temporary directory for extraction
    local temp_dir=$(mktemp -d)
    
    bbnote "  Extracting SWU to: $temp_dir"
    
    # Extract the CPIO archive
    if ! (cd "$temp_dir" && cpio -idm < "$input_swu" 2>&1); then
        rm -rf "$temp_dir"
        bbfatal "Failed to extract SWU archive"
    fi
    
    # Verify sw-description exists
    if [ ! -f "$temp_dir/sw-description" ]; then
        rm -rf "$temp_dir"
        bbfatal "sw-description not found in SWU archive"
    fi
    
    # Sign sw-description using the same openssl command as swupdate.bbclass
    # This is the RSA signing method from swupdate-common.bbclass:
    # openssl dgst -sha256 -sign <private_key> -passin file:<password_file> -out sw-description.sig sw-description
    bbnote "  Signing sw-description with RSA private key"
    if [ -f "${ADUC_PRIVATE_KEY_PASSWORD}" ]; then
        if ! openssl dgst -sha256 -sign "${ADUC_PRIVATE_KEY}" \
            -passin "file:${ADUC_PRIVATE_KEY_PASSWORD}" \
            -out "$temp_dir/sw-description.sig" "$temp_dir/sw-description"; then
            rm -rf "$temp_dir"
            bbfatal "Failed to sign sw-description"
        fi
    else
        # No password file - sign without password
        if ! openssl dgst -sha256 -sign "${ADUC_PRIVATE_KEY}" \
            -out "$temp_dir/sw-description.sig" "$temp_dir/sw-description"; then
            rm -rf "$temp_dir"
            bbfatal "Failed to sign sw-description"
        fi
    fi
    
    # Verify signature was created
    if [ ! -f "$temp_dir/sw-description.sig" ]; then
        rm -rf "$temp_dir"
        bbfatal "Signature file was not created"
    fi
    
    bbnote "  Signature created: sw-description.sig ($(stat -c%s $temp_dir/sw-description.sig) bytes)"
    
    # Get original file order from input SWU
    # CRITICAL: Must maintain exact file order with sw-description first, then sw-description.sig
    local file_list=$(cpio -it < "$input_swu" 2>/dev/null | grep -v "^$")
    
    # Create new SWU with signature
    # The order MUST be: sw-description, sw-description.sig, <other files>
    bbnote "  Creating signed SWU archive"
    (
        cd "$temp_dir"
        # Always put sw-description first
        echo "sw-description"
        # Then sw-description.sig
        echo "sw-description.sig"
        # Then all other files (excluding sw-description which we already added)
        echo "$file_list" | grep -v "^sw-description$" | grep -v "^sw-description.sig$"
    ) | (cd "$temp_dir" && cpio -o -H newc > "$output_swu" 2>/dev/null)
    
    local cpio_result=$?
    
    rm -rf "$temp_dir"
    
    if [ $cpio_result -ne 0 ]; then
        bbfatal "Failed to create signed SWU archive"
    fi
    
    # Verify output file was created
    if [ ! -f "$output_swu" ]; then
        bbfatal "Signed SWU file was not created: $output_swu"
    fi
    
    bbnote "  ✓ Signed SWU created: $(basename $output_swu) ($(stat -c%s $output_swu) bytes)"
}

# Helper shell function to generate delta using pre-created recompressed+signed SWU files
# This function assumes the recompressed files already exist (created by do_recompress_and_sign_v* tasks)
generate_delta_shell() {
    local recompressed_source="$1"
    local recompressed_target="$2"
    local delta_file="$3"
    local source_ver="$4"
    local target_ver="$5"
    
    if [ ! -f "$recompressed_source" ]; then
        bbfatal "Recompressed source file not found: $recompressed_source"
    fi
    
    if [ ! -f "$recompressed_target" ]; then
        bbfatal "Recompressed target file not found: $recompressed_target"
    fi
    
    mkdir -p ${DELTA_OUTPUT_DIR}
    
    # Create subdirectories for logs and working files
    local logs_dir="${DELTA_OUTPUT_DIR}/logs-v${source_ver}-v${target_ver}"
    local work_dir="${DELTA_OUTPUT_DIR}/working-v${source_ver}-v${target_ver}"
    mkdir -p "$logs_dir" "$work_dir"
    
    bbnote "====================================="
    bbnote "Generating delta from v$source_ver to v$target_ver"
    bbnote "====================================="
    bbnote "  Source (recompressed+signed): $recompressed_source"
    bbnote "  Target (recompressed+signed): $recompressed_target"
    bbnote "  Delta:  ${DELTA_OUTPUT_DIR}/$delta_file"
    bbnote "  Logs: $logs_dir"
    bbnote "  Working: $work_dir"
    bbnote "  Tool: diffgentool (C# .NET using libadudiffapi.so P/Invoke)"
    bbnote "====================================="
    
    # Set LD_LIBRARY_PATH so diffgentool can find libadudiffapi.so
    export LD_LIBRARY_PATH="${STAGING_LIBDIR_NATIVE}:${LD_LIBRARY_PATH}"
    export PATH="${STAGING_BINDIR_NATIVE}:${PATH}"
    
    # Generate delta using C# DiffGenTool (calls libadudiffapi.so via P/Invoke)
    diffgentool \
        "$recompressed_source" \
        "$recompressed_target" \
        "${DELTA_OUTPUT_DIR}/$delta_file" \
        "$work_dir" \
        || bbfatal "diffgentool failed"
    
    # Verify delta file was created
    if [ ! -f "${DELTA_OUTPUT_DIR}/$delta_file" ]; then
        bbfatal "Delta file was not created: ${DELTA_OUTPUT_DIR}/$delta_file"
    fi
    
    # Get file sizes and hashes
    SOURCE_SIZE=$(stat -c%s "$recompressed_source")
    TARGET_SIZE=$(stat -c%s "$recompressed_target")
    DELTA_SIZE=$(stat -c%s "${DELTA_OUTPUT_DIR}/$delta_file")
    SOURCE_HASH=$(sha256sum "$recompressed_source" | awk '{print $1}')
    TARGET_HASH=$(sha256sum "$recompressed_target" | awk '{print $1}')
    DELTA_HASH=$(sha256sum "${DELTA_OUTPUT_DIR}/$delta_file" | awk '{print $1}')
    if [ -f "${DELTA_OUTPUT_DIR}/$recompressed_file" ]; then
        RECOMPRESSED_SIZE=$(stat -c%s "${DELTA_OUTPUT_DIR}/$recompressed_file")
        RECOMPRESSED_HASH=$(sha256sum "${DELTA_OUTPUT_DIR}/$recompressed_file" | awk '{print $1}')
        bbnote "Recompressed file created: $recompressed_file ($RECOMPRESSED_SIZE bytes)"
    else
        bbwarn "Recompressed file not found: $recompressed_file"
    fi
    
    COMPRESSION=$(awk "BEGIN {printf \"%.2f\", ($DELTA_SIZE / $TARGET_SIZE) * 100}")
    
    # Create metadata file
    cat > "${DELTA_OUTPUT_DIR}/delta-info-v${source_ver}-to-v${target_ver}.txt" << EOF
Delta Information: v$source_ver → v$target_ver
Generated: $(date)
Source Version: $source_ver
Target Version: $target_ver
Source File: $(basename $source_file)
Target File: $(basename $target_file)
Delta Algorithm: Azure DiffGenTool (optimized for ADU)

File Sizes:
  Source: $SOURCE_SIZE bytes
  Target: $TARGET_SIZE bytes
  Delta: $DELTA_SIZE bytes
  Recompressed Target: $RECOMPRESSED_SIZE bytes
  Compression Ratio: $COMPRESSION%

File Hashes (SHA256):
  Source: $SOURCE_HASH
  Target: $TARGET_HASH
  Delta: $DELTA_HASH
  Recompressed Target: $RECOMPRESSED_HASH

ADU Service Import:
  - Import file: delta-manifest-v${source_ver}-to-v${target_ver}.importmanifest.json
  - Target SWU: $(basename ${DELTA_OUTPUT_DIR}/$recompressed_file)
  - Delta file: $(basename ${DELTA_OUTPUT_DIR}/$delta_file)

Device-side Application:
  - Delta processor applies: delta-diffgen <source> <target> <delta>
EOF
    
    bbnote "Delta generation completed successfully!"
    bbnote "  Delta size: $DELTA_SIZE bytes ($COMPRESSION% of target)"
    bbnote "  Source hash: $SOURCE_HASH"
    bbnote "  Recompressed target hash: $RECOMPRESSED_HASH"
}

# Task 1: Generate v1 -> v2 delta
do_generate_delta_v1_v2() {
    # Add native tools to PATH
    export PATH="${STAGING_BINDIR_NATIVE}:${PATH}"
    export LD_LIBRARY_PATH="${STAGING_LIBDIR_NATIVE}:${LD_LIBRARY_PATH}"
    
    bbnote "======================================================================"
    bbnote "TASK: do_generate_delta_v1_v2"
    bbnote "Using pre-created recompressed+signed SWU files"
    bbnote "======================================================================"
    
    # Use pre-created recompressed files from do_recompress_and_sign_v* tasks
    RECOMPRESSED_V1="${DELTA_OUTPUT_DIR}/adu-update-image-v1.0.0-recompressed.swu"
    RECOMPRESSED_V2="${DELTA_OUTPUT_DIR}/adu-update-image-v2.0.0-recompressed.swu"
    
    if [ ! -f "$RECOMPRESSED_V1" ]; then
        bbfatal "Recompressed v1 file not found: $RECOMPRESSED_V1 (should be created by do_recompress_and_sign_v1)"
    fi
    
    if [ ! -f "$RECOMPRESSED_V2" ]; then
        bbfatal "Recompressed v2 file not found: $RECOMPRESSED_V2 (should be created by do_recompress_and_sign_v2)"
    fi
    
    bbnote "Using recompressed files:"
    bbnote "  v1: $RECOMPRESSED_V1"
    bbnote "  v2: $RECOMPRESSED_V2"
    
    delta_file="${DELTA_FILE_NAME_V1_V2}"
    
    # Generate v1→v2 delta from recompressed+signed files
    generate_delta_shell "$RECOMPRESSED_V1" "$RECOMPRESSED_V2" \
        "$delta_file" "1.0.0" "2.0.0"
}

addtask generate_delta_v1_v2 after do_unpack before do_test_delta_v1_v2

# Task 2: Generate v2 -> v3 delta
do_generate_delta_v2_v3() {
    # Add native tools to PATH
    export PATH="${STAGING_BINDIR_NATIVE}:${PATH}"
    export LD_LIBRARY_PATH="${STAGING_LIBDIR_NATIVE}:${LD_LIBRARY_PATH}"
    
    bbnote "======================================================================"
    bbnote "TASK: do_generate_delta_v2_v3"
    bbnote "Using pre-created recompressed+signed SWU files"
    bbnote "======================================================================"
    
    # Use pre-created recompressed files from do_recompress_and_sign_v* tasks
    RECOMPRESSED_V2="${DELTA_OUTPUT_DIR}/adu-update-image-v2.0.0-recompressed.swu"
    RECOMPRESSED_V3="${DELTA_OUTPUT_DIR}/adu-update-image-v3.0.0-recompressed.swu"
    
    if [ ! -f "$RECOMPRESSED_V2" ]; then
        bbfatal "Recompressed v2 file not found: $RECOMPRESSED_V2 (should be created by do_recompress_and_sign_v2)"
    fi
    
    if [ ! -f "$RECOMPRESSED_V3" ]; then
        bbfatal "Recompressed v3 file not found: $RECOMPRESSED_V3 (should be created by do_recompress_and_sign_v3)"
    fi
    
    bbnote "Using recompressed files:"
    bbnote "  v2: $RECOMPRESSED_V2"
    bbnote "  v3: $RECOMPRESSED_V3"
    
    delta_file="${DELTA_FILE_NAME_V2_V3}"
    
    # Generate v2→v3 delta from recompressed+signed files
    generate_delta_shell "$RECOMPRESSED_V2" "$RECOMPRESSED_V3" \
        "$delta_file" "2.0.0" "3.0.0"
}

addtask generate_delta_v2_v3 after do_unpack before do_test_delta_v2_v3

# Task 3: Generate v1 -> v3 delta (skip v2 path)
do_generate_delta_v1_v3() {
    # Add native tools to PATH
    export PATH="${STAGING_BINDIR_NATIVE}:${PATH}"
    export LD_LIBRARY_PATH="${STAGING_LIBDIR_NATIVE}:${LD_LIBRARY_PATH}"
    
    bbnote "======================================================================"
    bbnote "TASK: do_generate_delta_v1_v3"
    bbnote "Using pre-created recompressed+signed SWU files"
    bbnote "======================================================================"
    
    # Use pre-created recompressed files from do_recompress_and_sign_v* tasks
    RECOMPRESSED_V1="${DELTA_OUTPUT_DIR}/adu-update-image-v1.0.0-recompressed.swu"
    RECOMPRESSED_V3="${DELTA_OUTPUT_DIR}/adu-update-image-v3.0.0-recompressed.swu"
    
    if [ ! -f "$RECOMPRESSED_V1" ]; then
        bbfatal "Recompressed v1 file not found: $RECOMPRESSED_V1 (should be created by do_recompress_and_sign_v1)"
    fi
    
    if [ ! -f "$RECOMPRESSED_V3" ]; then
        bbfatal "Recompressed v3 file not found: $RECOMPRESSED_V3 (should be created by do_recompress_and_sign_v3)"
    fi
    
    bbnote "Using recompressed files:"
    bbnote "  v1: $RECOMPRESSED_V1"
    bbnote "  v3: $RECOMPRESSED_V3"
    
    delta_file="${DELTA_FILE_NAME_V1_V3}"
    
    # Generate v1→v3 delta from recompressed+signed files
    generate_delta_shell "$RECOMPRESSED_V1" "$RECOMPRESSED_V3" \
        "$delta_file" "1.0.0" "3.0.0"
}

addtask generate_delta_v1_v3 after do_unpack before do_test_delta_v1_v3

# Helper function to generate ADU import manifest (JSON v5)
generate_import_manifest() {
    source_swu="$1"
    recompressed_swu="$2"
    delta_file="$3"
    source_ver="$4"
    target_ver="$5"
    manifest_file="$6"
    
    source_hash=$(sha256sum "$source_swu" | awk '{print $1}')
    target_hash=$(sha256sum "$recompressed_swu" | awk '{print $1}')
    delta_hash=$(sha256sum "$delta_file" | awk '{print $1}')
    recompressed_size=$(stat -c%s "$recompressed_swu")
    delta_size=$(stat -c%s "$delta_file")
    
    # Extract installedCriteria from adu-version file inside the target SWU
    # This ensures the manifest uses the actual version from the update package
    bbnote "Extracting installedCriteria from target SWU..."
    temp_extract="${WORKDIR}/temp_extract_manifest"
    rm -rf "$temp_extract"
    mkdir -p "$temp_extract"
    
    # Extract SWU to get adu-version file
    if cpio -i -F "$recompressed_swu" -D "$temp_extract" 2>/dev/null; then
        # Look for sw-description which contains adu-version reference
        if [ -f "$temp_extract/sw-description" ]; then
            # Extract version from first image's properties or fallback to target_ver
            installed_criteria=$(grep -oP 'version\s*=\s*"\K[^"]+' "$temp_extract/sw-description" | head -1)
            if [ -z "$installed_criteria" ]; then
                installed_criteria="$target_ver"
                bbwarn "Could not extract version from sw-description, using target version: $installed_criteria"
            else
                bbnote "Extracted installedCriteria from sw-description: $installed_criteria"
            fi
        else
            installed_criteria="$target_ver"
            bbwarn "sw-description not found, using target version: $installed_criteria"
        fi
    else
        installed_criteria="$target_ver"
        bbwarn "Could not extract SWU, using target version: $installed_criteria"
    fi
    rm -rf "$temp_extract"
    
    # Script file name for A/B updates
    SCRIPT_FILE="yocto-a-b-update.sh"
    SWU_FILE_NAME="adu-update-image-v${target_ver}-recompressed.swu"
    SCRIPT_ARGUMENTS="--software-version-file /etc/adu-version --swupdate-log-file /var/log/adu/swupdate.log"
    
    # Generate JSON import manifest
    printf '{\n' > "$manifest_file"
    printf '  "updateId": {\n' >> "$manifest_file"
    printf '    "provider": "microsoft",\n' >> "$manifest_file"
    printf '    "name": "adu-delta-update",\n' >> "$manifest_file"
    printf '    "version": "%s"\n' "$target_ver" >> "$manifest_file"
    printf '  },\n' >> "$manifest_file"
    printf '  "updateType": "microsoft/swupdate:2",\n' >> "$manifest_file"
    printf '  "compatibility": [\n' >> "$manifest_file"
    printf '    {\n' >> "$manifest_file"
    printf '      "manufacturer": "raspberrypi",\n' >> "$manifest_file"
    printf '      "model": "raspberrypi4-64"\n' >> "$manifest_file"
    printf '    }\n' >> "$manifest_file"
    printf '  ],\n' >> "$manifest_file"
    printf '  "instructions": {\n' >> "$manifest_file"
    printf '    "steps": [\n' >> "$manifest_file"
    printf '      {\n' >> "$manifest_file"
    printf '        "handler": "microsoft/swupdate:2",\n' >> "$manifest_file"
    printf '        "files": ["%s", "%s"],\n' "$SWU_FILE_NAME" "$SCRIPT_FILE" >> "$manifest_file"
    printf '        "handlerProperties": {\n' >> "$manifest_file"
    printf '          "installedCriteria": "%s",\n' "$installed_criteria" >> "$manifest_file"
    printf '          "swuFileName": "%s",\n' "$SWU_FILE_NAME" >> "$manifest_file"
    printf '          "scriptFileName": "%s",\n' "$SCRIPT_FILE" >> "$manifest_file"
    printf '          "arguments": "%s"\n' "$SCRIPT_ARGUMENTS" >> "$manifest_file"
    printf '        }\n' >> "$manifest_file"
    printf '      }\n' >> "$manifest_file"
    printf '    ]\n' >> "$manifest_file"
    printf '  },\n' >> "$manifest_file"
    printf '  "relatedFiles": [\n' >> "$manifest_file"
    printf '    {\n' >> "$manifest_file"
    printf '      "filename": "adu-delta-v%s-to-v%s.diff",\n' "$source_ver" "$target_ver" >> "$manifest_file"
    printf '      "sizeInBytes": %s,\n' "$delta_size" >> "$manifest_file"
    printf '      "hashes": {\n' >> "$manifest_file"
    printf '        "sha256": "%s"\n' "$delta_hash" >> "$manifest_file"
    printf '      },\n' >> "$manifest_file"
    printf '      "properties": {\n' >> "$manifest_file"
    printf '        "microsoft.sourceFileHashAlgorithm": "sha256",\n' >> "$manifest_file"
    printf '        "microsoft.sourceFileHash": "%s"\n' "$source_hash" >> "$manifest_file"
    printf '      }\n' >> "$manifest_file"
    printf '    }\n' >> "$manifest_file"
    printf '  ],\n' >> "$manifest_file"
    printf '  "downloadHandler": {\n' >> "$manifest_file"
    printf '    "id": "microsoft/delta:1"\n' >> "$manifest_file"
    printf '  }\n' >> "$manifest_file"
    printf '}\n' >> "$manifest_file"
    
    bbnote "Import manifest generated: $(basename $manifest_file)"
}

# Test task for v1 -> v2
do_test_delta_v1_v2() {
    # Add /usr/bin to PATH so bspatch is available
    export PATH="/usr/bin:${PATH}"
    
    # Find SWU files at task execution time
    SWU_V1=$(find ${DEPLOY_DIR_IMAGE} -maxdepth 1 -name 'adu-update-image-v1-*.swu' -type f | head -n1)
    SWU_V2=$(find ${DEPLOY_DIR_IMAGE} -maxdepth 1 -name 'adu-update-image-v2-*.swu' -type f | head -n1)
    
    if [ -z "$SWU_V1" ] || [ -z "$SWU_V2" ]; then
        bbfatal "SWU files not found for testing: v1=$SWU_V1 v2=$SWU_V2"
    fi
    
    bbnote "====================================="
    bbnote "Testing Delta: v1 -> v2"
    bbnote "====================================="
    
    DELTA_FILE="${DELTA_OUTPUT_DIR}/${DELTA_FILE_NAME_V1_V2}"
    RECOMPRESSED_V2="${DELTA_OUTPUT_DIR}/adu-update-image-v2.0.0-recompressed.swu"
    
    # Validate PAMZ format (magic bytes: 50 41 4d 5a)
    bbnote "Validating PAMZ format..."
    if [ ! -f "$DELTA_FILE" ]; then
        bbfatal "Delta file not found: $DELTA_FILE"
    fi
    
    MAGIC=$(hexdump -n 4 -e '4/1 "%02x" "\n"' "$DELTA_FILE")
    if [ "$MAGIC" != "50414d5a" ]; then
        bbfatal "Invalid PAMZ magic bytes! Expected: 50414d5a, Got: $MAGIC"
    fi
    
    bbnote "✓ PAMZ format validated (magic: $MAGIC)"
    
    # Verify recompressed target file exists
    if [ ! -f "$RECOMPRESSED_V2" ]; then
        bbfatal "Recompressed target not found: $RECOMPRESSED_V2"
    fi
    
    DELTA_SIZE=$(stat -c%s "$DELTA_FILE")
    RECOMP_SIZE=$(stat -c%s "$RECOMPRESSED_V2")
    COMPRESSION=$(awk "BEGIN {printf \"%.2f\", ($DELTA_SIZE / $RECOMP_SIZE) * 100}")
    
    bbnote "✓ PAMZ format validated"
    bbnote "  Delta size: $DELTA_SIZE bytes"
    bbnote "  Recompressed target: $RECOMP_SIZE bytes"
    bbnote "  Compression ratio: $COMPRESSION%"
    
    # Now apply the delta to verify it actually reconstructs correctly
    bbnote ""
    bbnote "Applying delta to verify reconstruction..."
    
    # Need recompressed source (v1)
    RECOMPRESSED_V1="${DELTA_OUTPUT_DIR}/adu-update-image-v1.0.0-recompressed.swu"
    if [ ! -f "$RECOMPRESSED_V1" ]; then
        bbfatal "Recompressed source (v1) not found: $RECOMPRESSED_V1"
    fi
    
    RECONSTRUCTED="${DELTA_OUTPUT_DIR}/reconstructed-v2.swu"
    
    # Set up library paths for applydiff
    export LD_LIBRARY_PATH="${STAGING_LIBDIR_NATIVE}:${LD_LIBRARY_PATH}"
    export PATH="${STAGING_BINDIR_NATIVE}:${PATH}"
    
    bbnote "  Source: $(basename $RECOMPRESSED_V1)"
    bbnote "  Delta: $(basename $DELTA_FILE)"
    bbnote "  Target: $(basename $RECOMPRESSED_V2)"
    bbnote "  Reconstructing to: $(basename $RECONSTRUCTED)"
    
    # Apply delta using processor's applydiff tool
    if ! applydiff "$RECOMPRESSED_V1" "$DELTA_FILE" "$RECONSTRUCTED"; then
        bbfatal "Failed to apply delta! applydiff returned error."
    fi
    
    # Verify reconstructed file matches expected target
    if [ ! -f "$RECONSTRUCTED" ]; then
        bbfatal "Reconstructed file was not created: $RECONSTRUCTED"
    fi
    
    RECON_HASH=$(sha256sum "$RECONSTRUCTED" | awk '{print $1}')
    TARGET_HASH=$(sha256sum "$RECOMPRESSED_V2" | awk '{print $1}')
    
    if [ "$RECON_HASH" = "$TARGET_HASH" ]; then
        bbnote "✓ SUCCESS: Reconstruction verified!"
        bbnote "  SHA256: $RECON_HASH"
    else
        bberror "FAILED: Reconstructed file does not match target!"
        bberror "  Expected: $TARGET_HASH"
        bberror "  Got:      $RECON_HASH"
        bbfatal "Delta reconstruction verification failed"
    fi
    
    # Clean up
    rm -f "$RECONSTRUCTED"
    bbnote "====================================="
    
    # Generate import manifest for this delta
    generate_import_manifest "$SWU_V1" "$SWU_V2" \
        "${DELTA_OUTPUT_DIR}/${DELTA_FILE_NAME_V1_V2}" "1.0.0" "2.0.0" \
        "${DELTA_OUTPUT_DIR}/delta-manifest-v1.0.0-to-v2.0.0.importmanifest.json"
}

addtask test_delta_v1_v2 after do_generate_delta_v1_v2 before do_deploy

# Test task for v2 -> v3
do_test_delta_v2_v3() {
    # Add /usr/bin to PATH so bspatch is available
    export PATH="/usr/bin:${PATH}"
    
    # Find SWU files at task execution time
    SWU_V2=$(find ${DEPLOY_DIR_IMAGE} -maxdepth 1 -name 'adu-update-image-v2-*.swu' -type f | head -n1)
    SWU_V3=$(find ${DEPLOY_DIR_IMAGE} -maxdepth 1 -name 'adu-update-image-v3-*.swu' -type f | head -n1)
    
    if [ -z "$SWU_V2" ] || [ -z "$SWU_V3" ]; then
        bbfatal "SWU files not found for testing: v2=$SWU_V2 v3=$SWU_V3"
    fi
    
    bbnote "====================================="
    bbnote "Testing Delta: v2 -> v3"
    bbnote "====================================="
    
    DELTA_FILE="${DELTA_OUTPUT_DIR}/${DELTA_FILE_NAME_V2_V3}"
    RECOMPRESSED_V3="${DELTA_OUTPUT_DIR}/adu-update-image-v3.0.0-recompressed.swu"
    
    # Validate PAMZ format (magic bytes: 50 41 4d 5a)
    bbnote "Validating PAMZ format..."
    if [ ! -f "$DELTA_FILE" ]; then
        bbfatal "Delta file not found: $DELTA_FILE"
    fi
    
    MAGIC=$(hexdump -n 4 -e '4/1 "%02x" "\n"' "$DELTA_FILE")
    if [ "$MAGIC" != "50414d5a" ]; then
        bbfatal "Invalid PAMZ magic bytes! Expected: 50414d5a, Got: $MAGIC"
    fi
    
    bbnote "✓ PAMZ format validated (magic: $MAGIC)"
    
    # Verify recompressed target file exists
    if [ ! -f "$RECOMPRESSED_V3" ]; then
        bbfatal "Recompressed target not found: $RECOMPRESSED_V3"
    fi
    
    DELTA_SIZE=$(stat -c%s "$DELTA_FILE")
    RECOMP_SIZE=$(stat -c%s "$RECOMPRESSED_V3")
    COMPRESSION=$(awk "BEGIN {printf \"%.2f\", ($DELTA_SIZE / $RECOMP_SIZE) * 100}")
    
    bbnote "✓ PAMZ format validated"
    bbnote "  Delta size: $DELTA_SIZE bytes"
    bbnote "  Recompressed target: $RECOMP_SIZE bytes"
    bbnote "  Compression ratio: $COMPRESSION%"
    
    # Now apply the delta to verify it actually reconstructs correctly
    bbnote ""
    bbnote "Applying delta to verify reconstruction..."
    
    # Need recompressed source (v2)
    RECOMPRESSED_V2="${DELTA_OUTPUT_DIR}/adu-update-image-v2.0.0-recompressed.swu"
    if [ ! -f "$RECOMPRESSED_V2" ]; then
        bbfatal "Recompressed source (v2) not found: $RECOMPRESSED_V2"
    fi
    
    RECONSTRUCTED="${DELTA_OUTPUT_DIR}/reconstructed-v3.swu"
    
    # Set up library paths for applydiff
    export LD_LIBRARY_PATH="${STAGING_LIBDIR_NATIVE}:${LD_LIBRARY_PATH}"
    export PATH="${STAGING_BINDIR_NATIVE}:${PATH}"
    
    bbnote "  Source: $(basename $RECOMPRESSED_V2)"
    bbnote "  Delta: $(basename $DELTA_FILE)"
    bbnote "  Target: $(basename $RECOMPRESSED_V3)"
    bbnote "  Reconstructing to: $(basename $RECONSTRUCTED)"
    
    # Apply delta using processor's applydiff tool
    if ! applydiff "$RECOMPRESSED_V2" "$DELTA_FILE" "$RECONSTRUCTED"; then
        bbfatal "Failed to apply delta! applydiff returned error."
    fi
    
    # Verify reconstructed file matches expected target
    if [ ! -f "$RECONSTRUCTED" ]; then
        bbfatal "Reconstructed file was not created: $RECONSTRUCTED"
    fi
    
    RECON_HASH=$(sha256sum "$RECONSTRUCTED" | awk '{print $1}')
    TARGET_HASH=$(sha256sum "$RECOMPRESSED_V3" | awk '{print $1}')
    
    if [ "$RECON_HASH" = "$TARGET_HASH" ]; then
        bbnote "✓ SUCCESS: Reconstruction verified!"
        bbnote "  SHA256: $RECON_HASH"
    else
        bberror "FAILED: Reconstructed file does not match target!"
        bberror "  Expected: $TARGET_HASH"
        bberror "  Got:      $RECON_HASH"
        bbfatal "Delta reconstruction verification failed"
    fi
    
    # Clean up
    rm -f "$RECONSTRUCTED"
    bbnote "====================================="
    
    # Generate import manifest for this delta
    generate_import_manifest "$SWU_V2" "$SWU_V3" \
        "${DELTA_OUTPUT_DIR}/${DELTA_FILE_NAME_V2_V3}" "2.0.0" "3.0.0" \
        "${DELTA_OUTPUT_DIR}/delta-manifest-v2.0.0-to-v3.0.0.importmanifest.json"
}

addtask test_delta_v2_v3 after do_generate_delta_v2_v3 before do_deploy

# Test task for v1 -> v3
do_test_delta_v1_v3() {
    # Add /usr/bin to PATH so bspatch is available
    export PATH="/usr/bin:${PATH}"
    
    # Find SWU files at task execution time
    SWU_V1=$(find ${DEPLOY_DIR_IMAGE} -maxdepth 1 -name 'adu-update-image-v1-*.swu' -type f | head -n1)
    SWU_V3=$(find ${DEPLOY_DIR_IMAGE} -maxdepth 1 -name 'adu-update-image-v3-*.swu' -type f | head -n1)
    
    if [ -z "$SWU_V1" ] || [ -z "$SWU_V3" ]; then
        bbfatal "SWU files not found for testing: v1=$SWU_V1 v3=$SWU_V3"
    fi
    
    bbnote "====================================="
    bbnote "Testing Delta: v1 -> v3 (skip v2)"
    bbnote "====================================="
    
    DELTA_FILE="${DELTA_OUTPUT_DIR}/${DELTA_FILE_NAME_V1_V3}"
    RECOMPRESSED_V3="${DELTA_OUTPUT_DIR}/adu-update-image-v3.0.0-recompressed.swu"
    
    # Validate PAMZ format (magic bytes: 50 41 4d 5a)
    bbnote "Validating PAMZ format..."
    if [ ! -f "$DELTA_FILE" ]; then
        bbfatal "Delta file not found: $DELTA_FILE"
    fi
    
    MAGIC=$(hexdump -n 4 -e '4/1 "%02x" "\n"' "$DELTA_FILE")
    if [ "$MAGIC" != "50414d5a" ]; then
        bbfatal "Invalid PAMZ magic bytes! Expected: 50414d5a, Got: $MAGIC"
    fi
    
    bbnote "✓ PAMZ format validated (magic: $MAGIC)"
    
    # Verify recompressed target file exists
    if [ ! -f "$RECOMPRESSED_V3" ]; then
        bbfatal "Recompressed target not found: $RECOMPRESSED_V3"
    fi
    
    DELTA_SIZE=$(stat -c%s "$DELTA_FILE")
    RECOMP_SIZE=$(stat -c%s "$RECOMPRESSED_V3")
    COMPRESSION=$(awk "BEGIN {printf \"%.2f\", ($DELTA_SIZE / $RECOMP_SIZE) * 100}")
    
    bbnote "✓ PAMZ format validated"
    bbnote "  Delta size: $DELTA_SIZE bytes"
    bbnote "  Recompressed target: $RECOMP_SIZE bytes"
    bbnote "  Compression ratio: $COMPRESSION%"
    
    # Now apply the delta to verify it actually reconstructs correctly
    bbnote ""
    bbnote "Applying delta to verify reconstruction..."
    
    # Need recompressed source (v1)
    RECOMPRESSED_V1="${DELTA_OUTPUT_DIR}/adu-update-image-v1.0.0-recompressed.swu"
    if [ ! -f "$RECOMPRESSED_V1" ]; then
        bbfatal "Recompressed source (v1) not found: $RECOMPRESSED_V1"
    fi
    
    RECONSTRUCTED="${DELTA_OUTPUT_DIR}/reconstructed-v3-from-v1.swu"
    
    # Set up library paths for applydiff
    export LD_LIBRARY_PATH="${STAGING_LIBDIR_NATIVE}:${LD_LIBRARY_PATH}"
    export PATH="${STAGING_BINDIR_NATIVE}:${PATH}"
    
    bbnote "  Source: $(basename $RECOMPRESSED_V1)"
    bbnote "  Delta: $(basename $DELTA_FILE)"
    bbnote "  Target: $(basename $RECOMPRESSED_V3)"
    bbnote "  Reconstructing to: $(basename $RECONSTRUCTED)"
    
    # Apply delta using processor's applydiff tool
    if ! applydiff "$RECOMPRESSED_V1" "$DELTA_FILE" "$RECONSTRUCTED"; then
        bbfatal "Failed to apply delta! applydiff returned error."
    fi
    
    # Verify reconstructed file matches expected target
    if [ ! -f "$RECONSTRUCTED" ]; then
        bbfatal "Reconstructed file was not created: $RECONSTRUCTED"
    fi
    
    RECON_HASH=$(sha256sum "$RECONSTRUCTED" | awk '{print $1}')
    TARGET_HASH=$(sha256sum "$RECOMPRESSED_V3" | awk '{print $1}')
    
    if [ "$RECON_HASH" = "$TARGET_HASH" ]; then
        bbnote "✓ SUCCESS: Reconstruction verified!"
        bbnote "  SHA256: $RECON_HASH"
    else
        bberror "FAILED: Reconstructed file does not match target!"
        bberror "  Expected: $TARGET_HASH"
        bberror "  Got:      $RECON_HASH"
        bbfatal "Delta reconstruction verification failed"
    fi
    
    # Clean up
    rm -f "$RECONSTRUCTED"
    bbnote "====================================="
    
    # Generate import manifest for this delta (skip v2 path)
    generate_import_manifest "$SWU_V1" "$SWU_V3" \
        "${DELTA_OUTPUT_DIR}/${DELTA_FILE_NAME_V1_V3}" "1.0.0" "3.0.0" \
        "${DELTA_OUTPUT_DIR}/delta-manifest-v1.0.0-to-v3.0.0.importmanifest.json"
}

addtask test_delta_v1_v3 after do_generate_delta_v1_v3 before do_deploy

do_deploy() {
    install -d ${DEPLOYDIR}
    
    bbnote "====================================="
    bbnote "do_deploy: Starting deployment for adu-delta-image"
    bbnote "====================================="
    bbnote "Recipe: ${PN}-${PV}-${PR}"
    bbnote "Source directory: ${DELTA_OUTPUT_DIR}"
    bbnote "Target directory: ${DEPLOYDIR}"
    bbnote ""
    
    # Check for existing files before cleanup
    bbnote "Checking for existing delta artifacts in deploy directory..."
    if ls ${DEPLOYDIR}/adu-delta-*.diff 1> /dev/null 2>&1; then
        bbnote "WARNING: Found existing .diff files (will be removed):"
        ls -lh ${DEPLOYDIR}/adu-delta-*.diff || true
    fi
    if ls ${DEPLOYDIR}/delta-manifest-*.importmanifest.json 1> /dev/null 2>&1; then
        bbnote "WARNING: Found existing .importmanifest.json files (will be removed):"
        ls -lh ${DEPLOYDIR}/delta-manifest-*.importmanifest.json || true
    fi
    if ls ${DEPLOYDIR}/statistics-*.txt 1> /dev/null 2>&1; then
        bbnote "WARNING: Found existing statistics-*.txt files (will be removed):"
        ls -lh ${DEPLOYDIR}/statistics-*.txt || true
    fi
    
    # Clean up any existing delta artifacts to avoid "file already exists" errors
    # This can happen if files were manually deployed or from previous runs
    bbnote "Cleaning up existing delta artifacts..."
    rm -f ${DEPLOYDIR}/adu-delta-*.diff
    rm -f ${DEPLOYDIR}/*-recompressed.swu
    rm -f ${DEPLOYDIR}/*-recompressed.swu.sha256
    rm -f ${DEPLOYDIR}/delta-manifest-*.importmanifest.json
    rm -f ${DEPLOYDIR}/statistics-*.txt
    bbnote "Cleanup complete."
    bbnote ""
    
    # Deploy delta files
    bbnote "Deploying delta files (.diff)..."
    if ls ${DELTA_OUTPUT_DIR}/*.diff 1> /dev/null 2>&1; then
        for diff_file in ${DELTA_OUTPUT_DIR}/*.diff; do
            bbnote "  Installing: $(basename $diff_file)"
            install -m 0644 "$diff_file" ${DEPLOYDIR}/
        done
    else
        bbnote "  No .diff files found in ${DELTA_OUTPUT_DIR}"
    fi
    
    # Deploy recompressed SWU files (for delta fallback)
    bbnote "Deploying recompressed SWU files..."
    if ls ${DELTA_OUTPUT_DIR}/*-recompressed.swu 1> /dev/null 2>&1; then
        for recomp_file in ${DELTA_OUTPUT_DIR}/*-recompressed.swu; do
            bbnote "  Installing: $(basename $recomp_file)"
            install -m 0644 "$recomp_file" ${DEPLOYDIR}/
            
            # Generate and deploy SHA256 hash file for verification
            sha256sum "$recomp_file" | awk '{print $1}' > "${DEPLOYDIR}/$(basename $recomp_file).sha256"
            bbnote "  Installing: $(basename $recomp_file).sha256"
        done
    else
        bbwarn "  No recompressed .swu files found in ${DELTA_OUTPUT_DIR}"
    fi
    
    # Deploy import manifests (JSON v5 for ADU service import)
    bbnote "Deploying import manifests (.importmanifest.json)..."
    if ls ${DELTA_OUTPUT_DIR}/*.importmanifest.json 1> /dev/null 2>&1; then
        for manifest_file in ${DELTA_OUTPUT_DIR}/*.importmanifest.json; do
            bbnote "  Installing: $(basename $manifest_file)"
            install -m 0644 "$manifest_file" ${DEPLOYDIR}/
        done
    else
        bbnote "  No .importmanifest.json files found in ${DELTA_OUTPUT_DIR}"
    fi
    
    # Deploy statistics files with unique names
    bbnote "Deploying statistics files..."
    for stats_file in ${DELTA_OUTPUT_DIR}/logs-v*/statistics.txt; do
        if [ -f "$stats_file" ]; then
            version_dir=$(basename $(dirname "$stats_file"))
            target_file="statistics-${version_dir}.txt"
            bbnote "  Installing: $target_file (from $version_dir/statistics.txt)"
            install -m 0644 "$stats_file" "${DEPLOYDIR}/$target_file"
        fi
    done
    
    bbnote ""
    bbnote "====================================="
    bbnote "Delta Update Artifacts Deployment Summary:"
    bbnote "====================================="
    bbnote "Location: ${DEPLOYDIR}/"
    bbnote "Deployed files:"
    ls -lh ${DEPLOYDIR}/ | grep -E "\\.diff|\\.json|statistics-.*\\.txt|-recompressed\\.swu" || true
    bbnote "====================================="
    bbnote "do_deploy: Completed successfully"
    bbnote "====================================="
}

do_deploy[depends] = ""
do_deploy[dirs] = "${DEPLOYDIR}"
do_deploy[umask] = "022"
# Deploy after delta verification
addtask deploy after do_verify_all_deltas before do_build

# Verify delta reconstruction using applydiff tool
# This ensures that applying each delta to its source produces the exact target
python do_verify_delta_v1_v2() {
    import subprocess
    import os
    
    delta_dir = d.getVar('DELTA_OUTPUT_DIR')
    staging_bindir = d.getVar('STAGING_BINDIR_NATIVE')
    staging_base = os.path.dirname(os.path.dirname(staging_bindir))
    
    source = os.path.join(delta_dir, 'adu-update-image-v1.0.0-recompressed.swu')
    delta = os.path.join(delta_dir, 'adu-delta-v1-to-v2.diff')
    target = os.path.join(delta_dir, 'adu-update-image-v2.0.0-recompressed.swu')
    reconstructed = os.path.join(delta_dir, 'v2-reconstructed-verify.swu')
    
    bb.note("=" * 80)
    bb.note("Verifying delta v1→v2 reconstruction")
    bb.note("=" * 80)
    
    # Build LD_LIBRARY_PATH from all native lib directories
    lib_paths = []
    sysroots_components = os.path.join(staging_base, 'sysroots-components', 'x86_64')
    if os.path.exists(sysroots_components):
        for root, dirs, files in os.walk(sysroots_components):
            if os.path.basename(root) == 'lib':
                lib_paths.append(root)
    
    env = os.environ.copy()
    env['LD_LIBRARY_PATH'] = ':'.join(lib_paths + [env.get('LD_LIBRARY_PATH', '')])
    env['PATH'] = staging_bindir + ':' + env.get('PATH', '')
    
    # Run applydiff
    cmd = ['applydiff', source, delta, reconstructed]
    bb.note(f"Running: {' '.join(cmd)}")
    
    try:
        result = subprocess.run(cmd, env=env, capture_output=True, text=True, check=True)
        bb.note(result.stdout)
        if result.stderr:
            bb.note(result.stderr)
    except subprocess.CalledProcessError as e:
        bb.fatal(f"applydiff failed: {e.stderr}")
    
    # Verify reconstruction matches target
    if not os.path.exists(reconstructed):
        bb.fatal("Reconstructed file was not created")
    
    # Compare SHA256 hashes
    import hashlib
    def sha256_file(path):
        h = hashlib.sha256()
        with open(path, 'rb') as f:
            for chunk in iter(lambda: f.read(8192), b''):
                h.update(chunk)
        return h.hexdigest()
    
    reconstructed_hash = sha256_file(reconstructed)
    target_hash = sha256_file(target)
    
    bb.note(f"Reconstructed SHA256: {reconstructed_hash}")
    bb.note(f"Target SHA256:        {target_hash}")
    
    if reconstructed_hash == target_hash:
        bb.note("✅ SUCCESS: v1 + delta-v1-v2 = v2 (verified)")
    else:
        bb.fatal("❌ FAIL: Reconstructed file does not match target v2")
    
    # Clean up temporary file
    os.remove(reconstructed)
    bb.note("=" * 80)
}

python do_verify_delta_v2_v3() {
    import subprocess
    import os
    
    delta_dir = d.getVar('DELTA_OUTPUT_DIR')
    staging_bindir = d.getVar('STAGING_BINDIR_NATIVE')
    staging_base = os.path.dirname(os.path.dirname(staging_bindir))
    
    source = os.path.join(delta_dir, 'adu-update-image-v2.0.0-recompressed.swu')
    delta = os.path.join(delta_dir, 'adu-delta-v2-to-v3.diff')
    target = os.path.join(delta_dir, 'adu-update-image-v3.0.0-recompressed.swu')
    reconstructed = os.path.join(delta_dir, 'v3-reconstructed-verify.swu')
    
    bb.note("=" * 80)
    bb.note("Verifying delta v2→v3 reconstruction")
    bb.note("=" * 80)
    
    # Build LD_LIBRARY_PATH from all native lib directories
    lib_paths = []
    sysroots_components = os.path.join(staging_base, 'sysroots-components', 'x86_64')
    if os.path.exists(sysroots_components):
        for root, dirs, files in os.walk(sysroots_components):
            if os.path.basename(root) == 'lib':
                lib_paths.append(root)
    
    env = os.environ.copy()
    env['LD_LIBRARY_PATH'] = ':'.join(lib_paths + [env.get('LD_LIBRARY_PATH', '')])
    env['PATH'] = staging_bindir + ':' + env.get('PATH', '')
    
    # Run applydiff
    cmd = ['applydiff', source, delta, reconstructed]
    bb.note(f"Running: {' '.join(cmd)}")
    
    try:
        result = subprocess.run(cmd, env=env, capture_output=True, text=True, check=True)
        bb.note(result.stdout)
        if result.stderr:
            bb.note(result.stderr)
    except subprocess.CalledProcessError as e:
        bb.fatal(f"applydiff failed: {e.stderr}")
    
    # Verify reconstruction matches target
    if not os.path.exists(reconstructed):
        bb.fatal("Reconstructed file was not created")
    
    # Compare SHA256 hashes
    import hashlib
    def sha256_file(path):
        h = hashlib.sha256()
        with open(path, 'rb') as f:
            for chunk in iter(lambda: f.read(8192), b''):
                h.update(chunk)
        return h.hexdigest()
    
    reconstructed_hash = sha256_file(reconstructed)
    target_hash = sha256_file(target)
    
    bb.note(f"Reconstructed SHA256: {reconstructed_hash}")
    bb.note(f"Target SHA256:        {target_hash}")
    
    if reconstructed_hash == target_hash:
        bb.note("✅ SUCCESS: v2 + delta-v2-v3 = v3 (verified)")
    else:
        bb.fatal("❌ FAIL: Reconstructed file does not match target v3")
    
    # Clean up temporary file
    os.remove(reconstructed)
    bb.note("=" * 80)
}

python do_verify_delta_v1_v3() {
    import subprocess
    import os
    
    delta_dir = d.getVar('DELTA_OUTPUT_DIR')
    staging_bindir = d.getVar('STAGING_BINDIR_NATIVE')
    staging_base = os.path.dirname(os.path.dirname(staging_bindir))
    
    source = os.path.join(delta_dir, 'adu-update-image-v1.0.0-recompressed.swu')
    delta = os.path.join(delta_dir, 'adu-delta-v1-to-v3.diff')
    target = os.path.join(delta_dir, 'adu-update-image-v3.0.0-recompressed.swu')
    reconstructed = os.path.join(delta_dir, 'v3-from-v1-reconstructed-verify.swu')
    
    bb.note("=" * 80)
    bb.note("Verifying delta v1→v3 reconstruction")
    bb.note("=" * 80)
    
    # Build LD_LIBRARY_PATH from all native lib directories
    lib_paths = []
    sysroots_components = os.path.join(staging_base, 'sysroots-components', 'x86_64')
    if os.path.exists(sysroots_components):
        for root, dirs, files in os.walk(sysroots_components):
            if os.path.basename(root) == 'lib':
                lib_paths.append(root)
    
    env = os.environ.copy()
    env['LD_LIBRARY_PATH'] = ':'.join(lib_paths + [env.get('LD_LIBRARY_PATH', '')])
    env['PATH'] = staging_bindir + ':' + env.get('PATH', '')
    
    # Run applydiff
    cmd = ['applydiff', source, delta, reconstructed]
    bb.note(f"Running: {' '.join(cmd)}")
    
    try:
        result = subprocess.run(cmd, env=env, capture_output=True, text=True, check=True)
        bb.note(result.stdout)
        if result.stderr:
            bb.note(result.stderr)
    except subprocess.CalledProcessError as e:
        bb.fatal(f"applydiff failed: {e.stderr}")
    
    # Verify reconstruction matches target
    if not os.path.exists(reconstructed):
        bb.fatal("Reconstructed file was not created")
    
    # Compare SHA256 hashes
    import hashlib
    def sha256_file(path):
        h = hashlib.sha256()
        with open(path, 'rb') as f:
            for chunk in iter(lambda: f.read(8192), b''):
                h.update(chunk)
        return h.hexdigest()
    
    reconstructed_hash = sha256_file(reconstructed)
    target_hash = sha256_file(target)
    
    bb.note(f"Reconstructed SHA256: {reconstructed_hash}")
    bb.note(f"Target SHA256:        {target_hash}")
    
    if reconstructed_hash == target_hash:
        bb.note("✅ SUCCESS: v1 + delta-v1-v3 = v3 (verified)")
    else:
        bb.fatal("❌ FAIL: Reconstructed file does not match target v3")
    
    # Clean up temporary file
    os.remove(reconstructed)
    bb.note("=" * 80)
}

# Verification tasks depend on delta generation and applydiff tool
do_verify_delta_v1_v2[depends] = "\
    adu-delta-image:do_generate_delta_v1_v2 \
    iot-hub-device-update-delta-processor-native:do_populate_sysroot \
"

do_verify_delta_v2_v3[depends] = "\
    adu-delta-image:do_generate_delta_v2_v3 \
    iot-hub-device-update-delta-processor-native:do_populate_sysroot \
"

do_verify_delta_v1_v3[depends] = "\
    adu-delta-image:do_generate_delta_v1_v3 \
    iot-hub-device-update-delta-processor-native:do_populate_sysroot \
"

addtask verify_delta_v1_v2 after do_generate_delta_v1_v2 before do_verify_all_deltas
addtask verify_delta_v2_v3 after do_generate_delta_v2_v3 before do_verify_all_deltas
addtask verify_delta_v1_v3 after do_generate_delta_v1_v3 before do_verify_all_deltas

# Aggregate verification task
python do_verify_all_deltas() {
    bb.note("=" * 80)
    bb.note("All delta verification tasks completed successfully!")
    bb.note("=" * 80)
}

addtask verify_all_deltas after do_verify_delta_v1_v2 do_verify_delta_v2_v3 do_verify_delta_v1_v3 before do_deploy

# No packages to create - this is a deploy-only recipe
PACKAGES = ""
EXCLUDE_FROM_WORLD = "1"

# Automatically clean delta artifacts when recipe is cleaned/rebuilt
clean_delta_artifacts () {
    # Find and remove delta .diff files from deploy directory
    for diff_file in ${DEPLOY_DIR_IMAGE}/adu-delta-v*.diff; do
        if [ -f "$diff_file" ]; then
            bbwarn "Cleaning stale delta artifact: $diff_file"
            rm -f "$diff_file"
        fi
    done
    
    # Also clean recompressed SWU files if they exist
    for swu_file in ${DEPLOY_DIR_IMAGE}/adu-update-image-v*-recompressed.swu; do
        if [ -f "$swu_file" ]; then
            bbwarn "Cleaning stale recompressed SWU: $swu_file"
            rm -f "$swu_file"
        fi
    done
    
    # Clean delta statistics and JSON files
    for stats_file in ${DEPLOY_DIR_IMAGE}/statistics-delta-*.txt ${DEPLOY_DIR_IMAGE}/delta-manifest-*.json; do
        if [ -f "$stats_file" ]; then
            bbwarn "Cleaning stale delta metadata: $stats_file"
            rm -f "$stats_file"
        fi
    done
}

CLEANFUNCS += "clean_delta_artifacts"
