# Common configuration for all ADU SWUpdate images
# This file contains shared settings used by adu-update-image-v1, v2, v3
# Each versioned recipe includes this file and only needs to set:
#   - IMAGE_LINK_NAME (e.g., "adu-update-image-v1")
#   - ADU_SOFTWARE_VERSION (e.g., "1.0.0.1", "1.0.0.2", "1.0.0.3")
#
# SIMPLIFIED APPROACH:
# Instead of building separate rootfs for each version, we:
# 1. Take adu-base-image.ext4.gz
# 2. Unpack it
# 3. Modify /etc/adu-version to target version
# 4. Repack as adu-update-image-vX.ext4.gz
# 5. Package into .swu

DESCRIPTION ?= "ADU swupdate image"
SECTION = ""
LICENSE = "CLOSED"

DEPENDS += "adu-base-image swupdate e2fsprogs-native"

# Task to create versioned filesystem from adu-base-image
# This unpacks adu-base-image.ext4.gz, modifies /etc/adu-version using debugfs, and repacks
do_create_versioned_image() {
    # Find the adu-base-image ext4.gz file
    BASE_IMAGE=$(find ${DEPLOY_DIR_IMAGE} -name 'adu-base-image-*.ext4.gz' -type f | head -n1)
    
    if [ -z "$BASE_IMAGE" ]; then
        bbfatal "Could not find adu-base-image-*.ext4.gz in ${DEPLOY_DIR_IMAGE}"
    fi
    
    bbnote "Creating versioned image for ${PN}"
    bbnote "  Base image: $BASE_IMAGE"
    bbnote "  Target version: ${ADU_SOFTWARE_VERSION}"
    
    # Create working directory
    WORK=${WORKDIR}/versioned-image
    rm -rf $WORK
    mkdir -p $WORK
    
    # Decompress the base image
    bbnote "Decompressing base image..."
    gunzip -c "$BASE_IMAGE" > $WORK/rootfs.ext4
    
    # Create new adu-version file
    echo "${ADU_SOFTWARE_VERSION}" > $WORK/adu-version.new
    
    # Use debugfs to write the file into ext4 filesystem
    # debugfs can modify ext4 filesystems without mounting
    bbnote "Updating /etc/adu-version to ${ADU_SOFTWARE_VERSION} using debugfs..."
    debugfs -w -R "rm /etc/adu-version" $WORK/rootfs.ext4 2>/dev/null || true
    debugfs -w -R "write $WORK/adu-version.new /etc/adu-version" $WORK/rootfs.ext4 || bbfatal "Failed to write /etc/adu-version"
    
    # Verify the change
    bbnote "Verifying /etc/adu-version content..."
    debugfs -R "cat /etc/adu-version" $WORK/rootfs.ext4 | head -1 | grep -q "${ADU_SOFTWARE_VERSION}" || bbwarn "Version verification failed"
    
    # Compress to deploy directory with correct name
    OUTPUT_FILE="${DEPLOY_DIR_IMAGE}/${PN}-${MACHINE}.ext4.gz"
    bbnote "Compressing to $OUTPUT_FILE..."
    gzip -c $WORK/rootfs.ext4 > $OUTPUT_FILE
    
    # Create symlink for swupdate class (IMAGE_LINK_NAME)
    cd ${DEPLOY_DIR_IMAGE}
    ln -sf $(basename $OUTPUT_FILE) ${IMAGE_LINK_NAME}.ext4.gz
    
    bbnote "Created versioned image: $OUTPUT_FILE"
    bbnote "  Symlink: ${IMAGE_LINK_NAME}.ext4.gz"
}

addtask create_versioned_image after do_unpack before do_swuimage
do_create_versioned_image[depends] += "adu-base-image:do_image_complete"
do_create_versioned_image[vardeps] += "ADU_SOFTWARE_VERSION"


# Source files - sw-description template
SRC_URI = "file://sw-description"

# Images and files to include in the .swu package
# Each update image packages its own filesystem with correct version
# This ensures each .swu contains the correct /etc/adu-version
SWUPDATE_IMAGES = "${PN}"

# Set filesystem type for this image
# Cannot use ${PN} in key, so we use python to set it dynamically
python __anonymous() {
    pn = d.getVar('PN')
    d.setVarFlag('SWUPDATE_IMAGES_FSTYPES', pn, '.ext4.gz')
}

# RSA signing configuration
# ADUC_PRIVATE_KEY - private key (.pem) file
# ADUC_PRIVATE_KEY_PASSWORD - private key password (.pass) file
# Generated RSA key with password using:
#   openssl genrsa -aes256 -passout file:priv.pass -out priv.pem
SWUPDATE_SIGNING = "RSA"
SWUPDATE_PRIVATE_KEY = "${ADUC_PRIVATE_KEY}"
SWUPDATE_PASSWORD_FILE = "${ADUC_PRIVATE_KEY_PASSWORD}"

# Inherit swupdate class to get SWUpdate packaging functionality
# The swupdate class automatically deploys .swu files to DEPLOY_DIR_IMAGE
inherit swupdate

# Inherit timestamp validation to ensure this image is rebuilt when base image changes
# Only enabled when delta update feature is turned on (WITH_FEATURE_DELTA_UPDATE='1')
# This prevents accidentally deploying update packages with stale base images
inherit ${@bb.utils.contains('WITH_FEATURE_DELTA_UPDATE', '1', 'adu-timestamp-check', '', d)}

# The swupdate class's do_deploy conflicts with old files from previous builds
# because cleansstate doesn't clean the deploy directory.
# Solution: Remove old deployed files in a separate task before deploy
python do_clean_old_deploys() {
    import glob
    import os
    
    deploy_dir = d.getVar('DEPLOY_DIR_IMAGE')
    pn = d.getVar('PN')
    
    # Remove old SWU files and manifests for this recipe
    old_swu_pattern = os.path.join(deploy_dir, pn + '*.swu')
    old_manifest_pattern = os.path.join(deploy_dir, 'manifest-*-' + pn + '.swuimage')
    
    for pattern in [old_swu_pattern, old_manifest_pattern]:
        for old_file in glob.glob(pattern):
            bb.note("Removing old deployed file: %s" % old_file)
            try:
                os.remove(old_file)
            except OSError as e:
                bb.warn("Could not remove %s: %s" % (old_file, e))
}

addtask clean_old_deploys before do_deploy after do_swuimage

# Inherit timestamp validation to ensure this image is rebuilt when base image changes
inherit adu-timestamp-check

# Force rebuild when ADU_SOFTWARE_VERSION changes
# This ensures each update image has the correct version in /etc/adu-version
do_swuimage[vardeps] += "ADU_SOFTWARE_VERSION"
do_image_complete[vardeps] += "ADU_SOFTWARE_VERSION"

# Python task to log version being built with comprehensive tracing
python do_set_version() {
    import os
    pn = d.getVar('PN')
    version = d.getVar('ADU_SOFTWARE_VERSION')
    image_name = d.getVar('IMAGE_LINK_NAME')
    image_basename = d.getVar('IMAGE_BASENAME') or 'N/A'
    
    bb.note("======================================================================")
    bb.note("TASK: do_set_version")
    bb.note("RECIPE: %s" % pn)
    bb.note("======================================================================")
    bb.note("Building ADU Update Image:")
    bb.note("  Recipe Name (PN): %s" % pn)
    bb.note("  Image Link Name: %s" % image_name)
    bb.note("  Software Version: %s" % version)
    bb.note("  Image Basename: %s" % image_basename)
    bb.note("======================================================================")
}

addtask set_version before do_swuimage after do_unpack

# Deploy the .adu-version file to the deploy directory
# This allows easy reference to the installedCriteria without extracting the .swu file
# File format: ${IMAGE_LINK_NAME}.adu-version (e.g., adu-update-image-v1.adu-version)
python do_deploy_adu_version() {
    import os
    import subprocess
    
    deploy_dir = d.getVar('DEPLOY_DIR_IMAGE')
    image_name = d.getVar('IMAGE_LINK_NAME')
    version = d.getVar('ADU_SOFTWARE_VERSION')
    
    if not image_name or not version:
        bb.warn("IMAGE_LINK_NAME or ADU_SOFTWARE_VERSION not set, skipping adu-version deployment")
        return
    
    # Construct output filename
    version_file = os.path.join(deploy_dir, f"{image_name}.adu-version")
    
    bb.note("======================================================================")
    bb.note("TASK: do_deploy_adu_version")
    bb.note("======================================================================")
    bb.note(f"Deploying ADU version file:")
    bb.note(f"  File: {version_file}")
    bb.note(f"  Version: {version}")
    bb.note("======================================================================")
    
    # Write version to file
    try:
        with open(version_file, 'w') as f:
            f.write(version + '\n')
        bb.note(f"Successfully deployed: {version_file}")
    except Exception as e:
        bb.warn(f"Failed to deploy adu-version file: {e}")
}

addtask deploy_adu_version after do_deploy before do_build
