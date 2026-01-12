# Generates ADU import manifests for v1, v2, and delta updates
# These manifests can be imported into Azure Device Update for E2E testing

DESCRIPTION = "ADU Import Manifest Generator"
LICENSE = "CLOSED"

# Depend on both versioned images and delta
DEPENDS = "adu-update-image-v1 adu-update-image-v2 adu-delta-image"

do_generate_manifests[depends] = "\
    adu-update-image-v1:do_swuimage \
    adu-update-image-v2:do_swuimage \
    adu-delta-image:do_deploy \
"

SRC_URI = ""

inherit deploy

MANIFEST_OUTPUT_DIR = "${WORKDIR}/manifests"

# ADU manifest template configuration
ADU_PROVIDER ?= "Contoso"
ADU_MODEL ?= "RaspberryPi"
ADU_COMPATIBILITY_HARDWARE ?= "1.0"

# Base version (will be set from BASE_ADU_SOFTWARE_VERSION)
BASE_ADU_SOFTWARE_VERSION ??= "1.0.0"

python () {
    # Calculate v1 and v2 versions
    base_version = d.getVar('BASE_ADU_SOFTWARE_VERSION')
    d.setVar('V1_VERSION', base_version)
    
    try:
        parts = base_version.split('.')
        if len(parts) == 3:
            major, minor, patch = parts
            new_patch = str(int(patch) + 1)
            v2_version = f"{major}.{minor}.{new_patch}"
        else:
            v2_version = base_version
    except:
        v2_version = base_version
    
    d.setVar('V2_VERSION', v2_version)
}

python do_find_files() {
    import os
    import glob
    
    deploy_dir = d.getVar('DEPLOY_DIR_IMAGE')
    
    # Find v1, v2 .swu files and delta
    v1_pattern = os.path.join(deploy_dir, '*update-image-v1*.swu')
    v2_pattern = os.path.join(deploy_dir, '*update-image-v2*.swu')
    delta_pattern = os.path.join(deploy_dir, 'adu-delta-v1-to-v2.diff')
    
    v1_files = glob.glob(v1_pattern)
    v2_files = glob.glob(v2_pattern)
    
    if v1_files:
        d.setVar('SWU_V1_FILE', v1_files[0])
        d.setVar('SWU_V1_FILENAME', os.path.basename(v1_files[0]))
    
    if v2_files:
        d.setVar('SWU_V2_FILE', v2_files[0])
        d.setVar('SWU_V2_FILENAME', os.path.basename(v2_files[0]))
    
    if os.path.exists(delta_pattern):
        d.setVar('DELTA_FILE', delta_pattern)
        d.setVar('DELTA_FILENAME', os.path.basename(delta_pattern))
}

addtask find_files after do_unpack before do_generate_manifests

python do_generate_manifests() {
    import os
    import json
    import hashlib
    from datetime import datetime
    
    manifest_dir = d.getVar('WORKDIR') + '/manifests'
    os.makedirs(manifest_dir, exist_ok=True)
    
    # Get variables
    provider = d.getVar('ADU_PROVIDER')
    model = d.getVar('ADU_MODEL')
    v1_ver = d.getVar('V1_VERSION')
    v2_ver = d.getVar('V2_VERSION')
    hw_compat = d.getVar('ADU_COMPATIBILITY_HARDWARE')
    
    v1_file = d.getVar('SWU_V1_FILE')
    v2_file = d.getVar('SWU_V2_FILE')
    delta_file = d.getVar('DELTA_FILE')
    
    v1_filename = d.getVar('SWU_V1_FILENAME')
    v2_filename = d.getVar('SWU_V2_FILENAME')
    delta_filename = d.getVar('DELTA_FILENAME')
    
    def get_file_info(filepath):
        if not filepath or not os.path.exists(filepath):
            return None, None
        size = os.path.getsize(filepath)
        sha256 = hashlib.sha256()
        with open(filepath, 'rb') as f:
            for chunk in iter(lambda: f.read(4096), b''):
                sha256.update(chunk)
        return size, sha256.hexdigest()
    
    v1_size, v1_sha256 = get_file_info(v1_file)
    v2_size, v2_sha256 = get_file_info(v2_file)
    delta_size, delta_sha256 = get_file_info(delta_file)
    
    bb.note("Generating ADU import manifests...")
    bb.note(f"  Provider: {provider}")
    bb.note(f"  Model: {model}")
    bb.note(f"  v1 Version: {v1_ver}")
    bb.note(f"  v2 Version: {v2_ver}")
    
    created_time = datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ')
    
    # Manifest 1: Full v1 Update
    manifest_v1 = {
        "updateId": {
            "provider": provider,
            "name": model,
            "version": v1_ver
        },
        "description": f"ADU Update v{v1_ver} (Baseline for Delta Testing)",
        "compatibility": [{
            "deviceManufacturer": provider,
            "deviceModel": model,
            "hardware": hw_compat
        }],
        "instructions": {
            "steps": [{
                "handler": "microsoft/swupdate:1",
                "files": [v1_filename],
                "handlerProperties": {
                    "installedCriteria": v1_ver,
                    "scriptFileName": v1_filename
                }
            }]
        },
        "files": [{
            "filename": v1_filename,
            "sizeInBytes": v1_size,
            "hashes": {"sha256": v1_sha256}
        }],
        "createdDateTime": created_time,
        "manifestVersion": "5.0"
    }
    
    # Manifest 2: Full v2 Update
    manifest_v2_full = {
        "updateId": {
            "provider": provider,
            "name": model,
            "version": v2_ver
        },
        "description": f"ADU Update v{v2_ver} (Full Update)",
        "compatibility": [{
            "deviceManufacturer": provider,
            "deviceModel": model,
            "hardware": hw_compat
        }],
        "instructions": {
            "steps": [{
                "handler": "microsoft/swupdate:1",
                "files": [v2_filename],
                "handlerProperties": {
                    "installedCriteria": v2_ver,
                    "scriptFileName": v2_filename
                }
            }]
        },
        "files": [{
            "filename": v2_filename,
            "sizeInBytes": v2_size,
            "hashes": {"sha256": v2_sha256}
        }],
        "createdDateTime": created_time,
        "manifestVersion": "5.0"
    }
    
    # Manifest 3: Delta v1→v2 Update
    manifest_v2_delta = {
        "updateId": {
            "provider": provider,
            "name": model,
            "version": v2_ver
        },
        "description": f"ADU Delta Update v{v1_ver}→v{v2_ver} (Delta Update)",
        "compatibility": [{
            "deviceManufacturer": provider,
            "deviceModel": model,
            "hardware": hw_compat
        }],
        "instructions": {
            "steps": [{
                "handler": "microsoft/delta:1",
                "files": [delta_filename],
                "handlerProperties": {
                    "installedCriteria": v2_ver,
                    "sourceVersion": v1_ver,
                    "targetVersion": v2_ver,
                    "deltaFileName": delta_filename
                }
            }]
        },
        "files": [{
            "filename": delta_filename,
            "sizeInBytes": delta_size,
            "hashes": {"sha256": delta_sha256},
            "relatedFiles": [{
                "filename": v2_filename,
                "sizeInBytes": v2_size,
                "hashes": {"sha256": v2_sha256},
                "properties": {
                    "microsoft.sourceFileHashAlgorithm": "sha256",
                    "microsoft.sourceFileHash": v1_sha256
                }
            }]
        }],
        "createdDateTime": created_time,
        "manifestVersion": "5.0"
    }
    
    # Write manifest files
    with open(os.path.join(manifest_dir, 'import-manifest-v1-full.json'), 'w') as f:
        json.dump(manifest_v1, f, indent=2)
    
    with open(os.path.join(manifest_dir, 'import-manifest-v2-full.json'), 'w') as f:
        json.dump(manifest_v2_full, f, indent=2)
    
    with open(os.path.join(manifest_dir, 'import-manifest-v2-delta.json'), 'w') as f:
        json.dump(manifest_v2_delta, f, indent=2)
    
    # Create README
    readme_content = f'''# ADU Import Manifests

This directory contains import manifests for Azure Device Update (ADU) end-to-end testing.

## Files Generated

1. **import-manifest-v1-full.json** - Full update to v{v1_ver} (baseline)
2. **import-manifest-v2-full.json** - Full update to v{v2_ver}
3. **import-manifest-v2-delta.json** - Delta update from v{v1_ver} to v{v2_ver}

## Quick Start

### Import v1 (Baseline)
```bash
az iot du update import \\
  --account <adu-account> \\
  --instance <adu-instance> \\
  --update-file import-manifest-v1-full.json \\
  --file ../adu-update-image-v1-*.swu
```

### Import v2 Delta Update
```bash
az iot du update import \\
  --account <adu-account> \\
  --instance <adu-instance> \\
  --update-file import-manifest-v2-delta.json \\
  --file ../adu-delta-v1-to-v2.diff \\
  --file ../adu-update-image-v2-*.swu
```

### Deploy to Device
```bash
az iot du device deployment create \\
  --account <adu-account> \\
  --instance <adu-instance> \\
  --deployment-id "deploy-v2-delta" \\
  --group-id <device-group> \\
  --update-provider "{provider}" \\
  --update-name "{model}" \\
  --update-version "{v2_ver}"
```

## Expected Results

- **Delta file size**: 5-40% of full update
- **Bandwidth savings**: 60-95% vs full update
- **Success rate**: Should match full update reliability

For complete E2E testing instructions, see:
- `yocto/meta-raspberrypi-adu/README-E2E-TESTING.md`
'''
    
    with open(os.path.join(manifest_dir, 'README-IMPORT.md'), 'w') as f:
        f.write(readme_content)
    
    d.setVar('MANIFEST_OUTPUT_DIR', manifest_dir)
    
    bb.note("===================================")
    bb.note("ADU Import Manifests Generated!")
    bb.note("===================================")
    bb.note(f"Output: {manifest_dir}/")
    bb.note("  • import-manifest-v1-full.json")
    bb.note("  • import-manifest-v2-full.json")
    bb.note("  • import-manifest-v2-delta.json")
    bb.note("  • README-IMPORT.md")
    bb.note("===================================")
}

addtask generate_manifests after do_find_files before do_deploy

do_deploy() {
    install -d ${DEPLOYDIR}/manifests
    install -m 0644 ${MANIFEST_OUTPUT_DIR}/*.json ${DEPLOYDIR}/manifests/
    install -m 0644 ${MANIFEST_OUTPUT_DIR}/README-IMPORT.md ${DEPLOYDIR}/manifests/
    
    bbnote "Import manifests deployed to: ${DEPLOYDIR}/manifests/"
}

addtask deploy after do_generate_manifests before do_build

PACKAGES = ""
