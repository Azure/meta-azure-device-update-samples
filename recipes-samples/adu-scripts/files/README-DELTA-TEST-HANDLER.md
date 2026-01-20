# Microsoft Delta Source Caching Handler

## Purpose

ADU script handler that installs updates and caches recompressed SWU files as delta sources. This handler:
1. Installs full SWU updates via SWUpdate
2. Caches the recompressed SWU file for delta reconstruction
3. **Verifies** the recompressed SWU was successfully cached
4. Writes proper ADUC results to the result file as required by the ADU agent
5. Enables efficient delta updates by maintaining source files for future delta reconstruction

## Why Delta Source Caching Matters

Delta updates can reduce bandwidth usage by 40-95% by downloading only the differences between versions. However, this requires:

1. **Source File**: The currently installed version in a specific format (recompressed with zstd)
2. **Delta File**: A binary diff between source and target versions
3. **Reconstruction**: Applying the delta to the source to produce the target

This handler ensures that every full update automatically caches its recompressed version, making subsequent delta updates possible.

**Example:**
- Full update v1.0 → v2.0: Download 800MB
- Delta update v2.0 → v3.0: Download 50MB (using cached v2.0 as source)
- **Savings**: 750MB (93% reduction)

## Location

Installed to: `/usr/lib/adu/microsoft-delta-source-caching.sh`

## Usage

This script is called by the ADU agent via `adu-shell`. It should **not** be called directly.

### Import Manifest Configuration

```json
{
  "updateId": {
    "provider": "Contoso",
    "name": "RaspberryPi",
    "version": "1.0.0"
  },
  "instructions": {
    "steps": [
      {
        "handler": "microsoft/script:1",
        "files": ["adu-update-image-v1.swu", "adu-update-image-v1-recompressed.swu"],
        "handlerProperties": {
          "scriptFileName": "microsoft-delta-source-caching.sh",
          "installedCriteria": "1.0.0",
          "arguments": "--image-file adu-update-image-v1.swu"
        }
      }
    ]
  }
}
```

### Handler Properties

| Property | Required | Description |
|----------|----------|-------------|
| `scriptFileName` | Yes | Must be `microsoft-delta-source-caching.sh` |
| `installedCriteria` | Yes | Version string to check (matches `/etc/adu-version`) |
| `arguments` | Yes | Must include `--image-file <swu_filename>` |

### Files Array

Must include **both**:
1. Main SWU file (e.g., `adu-update-image-v1.swu`)
2. Recompressed SWU file (e.g., `adu-update-image-v1-recompressed.swu`)

The recompressed file will be automatically cached to `/var/lib/adu/downloads/delta-cache/` for use by subsequent delta updates.

## Delta Update Workflow

### Update 1: Full Update (v0 → v1.0)

**Scenario:** Device has factory image v0, deploying first update v1.0

```
Files in Update Package:
  - adu-update-image-v1.swu (800MB)
  - adu-update-image-v1-recompressed.swu (800MB, zstd compressed)

Workflow:
  1. is-installed: Check if v1.0.0 installed
     └─> Read /etc/adu-version → "0.0.0"
     └─> Return exit code 900 (not installed)

  2. download: Verify files downloaded by DO
     └─> Check adu-update-image-v1.swu exists in work folder
     └─> Check adu-update-image-v1-recompressed.swu exists
     └─> Return exit code 0 (success)

  3. install:
     a) Execute: swupdate -i adu-update-image-v1.swu -e stable,copy1
        └─> Writes v1.0 rootfs to inactive partition
        └─> Updates U-Boot environment to boot from new partition
     
     b) Cache recompressed file for future delta updates:
        └─> Source: adu-update-image-v1-recompressed.swu
        └─> Destination: /var/lib/adu/downloads/delta-cache/
        └─> Verify file exists in cache (MANDATORY CHECK)
        └─> If cache fails: Return error 0x30102004
     
     c) Return exit code 0 (success)

  4. apply: Reboot to new partition
     └─> System restarts
     └─> U-Boot boots from updated partition
     └─> /etc/adu-version now reads "1.0.0"

Result: Device now has v1.0.0 installed with v1.0-recompressed.swu cached for delta updates
```

### Update 2: Delta Update (v1.0 → v2.0)

**Scenario:** Device has v1.0.0 installed (with cached recompressed source), deploying v2.0 via delta

```
Files in Update Package:
  - adu-delta-v1-to-v2.diff (50MB, binary delta)
  - adu-update-image-v2.swu (800MB, fallback if delta fails)
  - adu-update-image-v2-recompressed.swu (not downloaded initially)

Delta Reconstruction (handled by microsoft-delta-download-handler):
  Source:  /var/lib/adu/downloads/delta-cache/adu-update-image-v1-recompressed.swu (cached from v1.0 install)
  Diff:    adu-delta-v1-to-v2.diff (downloaded, 50MB)
  Command: bspatch v1-recompressed.swu v2-recompressed.swu v1-to-v2.diff
  Output:  adu-update-image-v2-recompressed.swu (reconstructed, 800MB)
  Verify:  SHA256 hash matches manifest → Success
  Result:  Download handler returns SuccessSkipDownload (agent won't download 800MB full file)

Workflow:
  1. is-installed: Check if v2.0.0 installed
     └─> Read /etc/adu-version → "1.0.0"
     └─> Return exit code 900 (not installed)

  2. download: Delta download handler active
     └─> Check cache for v1.0 source → Found!
     └─> Download only the diff (50MB vs 800MB)
     └─> Reconstruct v2.0-recompressed.swu from cache + diff
     └─> Verify reconstructed file hash
     └─> Place v2.0-recompressed.swu in work folder
     └─> Return SuccessSkipDownload (full download not needed)

  3. install:
     a) Execute: swupdate -i adu-update-image-v2-recompressed.swu -e stable,copy1
        └─> Writes v2.0 rootfs to inactive partition
        └─> Updates U-Boot to boot from new partition
     
     b) Cache v2.0 recompressed file for future delta updates (v2.0 → v3.0):
        └─> Source: adu-update-image-v2-recompressed.swu (already in work folder from reconstruction)
        └─> Destination: /var/lib/adu/downloads/delta-cache/
        └─> Verify cached successfully
     
     c) Return exit code 0

  4. apply: Reboot
     └─> Boot from updated partition
     └─> /etc/adu-version now reads "2.0.0"

Result: 
  - Device now has v2.0.0 installed
  - Bandwidth saved: 750MB (93% reduction)
  - v2.0-recompressed.swu cached for future delta to v3.0
```

### Update 3: Multi-Version Delta (v2.0 → v3.0)

**Scenario:** Demonstrating multiple delta paths in one manifest

```
Files in Update Package:
  - adu-delta-v1-to-v3.diff (150MB, for devices on v1.0)
  - adu-delta-v2-to-v3.diff (60MB, for devices on v2.0)
  - adu-update-image-v3.swu (800MB, fallback)

Update Manifest (relatedFiles):
  "relatedFiles": [
    {
      "filename": "adu-delta-v2-to-v3.diff",
      "properties": {
        "microsoft.sourceVersion": "2.0.0",
        "microsoft.sourceFileHash": "sha256:v2_hash..."
      }
    },
    {
      "filename": "adu-delta-v1-to-v3.diff",
      "properties": {
        "microsoft.sourceVersion": "1.0.0",
        "microsoft.sourceFileHash": "sha256:v1_hash..."
      }
    }
  ]

Delta Handler Logic:
  1. Iterate through relatedFiles in order
  2. Check cache for v2.0 source (matches current device version)
     └─> Found! Hash matches
  3. Download adu-delta-v2-to-v3.diff (60MB)
     └─> Skip v1-to-v3 delta (not needed)
  4. Reconstruct v3.0 from cached v2.0 + diff
  5. Return SuccessSkipDownload

Result: Optimal delta path chosen automatically based on cached source
```

### Fallback to Full Download

**Scenario:** Delta reconstruction fails, system falls back to full download

```
Delta Attempt:
  1. Check cache for source → Found
  2. Download diff file → Success
  3. Reconstruct target → Failed (corruption, space, or hash mismatch)
  4. Return error code (not SuccessSkipDownload)

Fallback:
  1. ADU agent receives error from delta handler
  2. Agent proceeds with full download
     └─> Download adu-update-image-v3.swu (800MB)
  3. Install proceeds with full file
  4. Cache v3.0-recompressed.swu for future deltas

Result: Update completes successfully despite delta failure
```

## Actions

### is-installed
Checks if the specified version is installed by comparing with `/etc/adu-version`.

**Arguments:**
- `--is-installed <version>`
- `--result-file <path>`

**Returns:**
- Exit code `0` if installed
- Exit code `900` if not installed

**ADUC Result:**
```json
{"resultCode":0, "extendedResultCode":0, "resultDetails":"..."}
```

### download
Verifies that the image file has been downloaded by Delivery Optimization.

**Arguments:**
- `--download`
- `--image-file <path>`
- `--result-file <path>`

**Returns:**
- Exit code `0` on success
- Exit code `1` on failure

### install
Installs the update using SWUpdate and caches the recompressed SWU for delta updates.

**Arguments:**
- `--install`
- `--image-file <path>`
- `--result-file <path>`

**Actions:**
1. Verifies image file exists
2. Executes `swupdate -i <image_file> -e stable,copy1`
3. Looks for `<image_file>-recompressed.swu`
4. Copies recompressed SWU to `/var/lib/adu/downloads/delta-cache/`
5. **Verifies** the cached file exists in delta cache

**Returns:**
- Exit code `0` on success
- Exit code `1` on failure

**Note:** Failure to cache the recompressed SWU is **FATAL** - the install will fail with error code 0x30102004.

### apply
Reboots the system to activate the newly installed update.

**Arguments:**
- `--apply`
- `--result-file <path>`

**Returns:** Does not return (system reboots)

## Result File Format

All actions write an ADUC result JSON to the specified `--result-file`:

```json
{
  "resultCode": 0,
  "extendedResultCode": 0,
  "resultDetails": "Success message or error details"
}
```

### Result Codes

| resultCode | extendedResultCode | Meaning |
|------------|-------------------|---------|
| 0 | 0 | Success |
| 0 | 0x30102001 | Download verification failed |
| 0 | 0x30102002 | Install failed (file not found) |
| 0 | 0x30102003 | SWUpdate execution failed || 0 | 0x30102004 | Delta source caching failed |
**Note:** Extended result codes use range `0x30102000-0x30102FFF` to avoid conflicts with other handlers.

## Log Files

| File | Purpose |
|------|---------|
| `/adu/logs/delta-source-caching.log` | Detailed execution log |
| `/adu/logs/delta-source-caching.output` | Summary output |
| `/adu/logs/delta-source-caching.result.json` | ADUC result (default location) |

## Delta Cache

| Path | Contents |
|------|----------|
| `/var/lib/adu/downloads/delta-cache/` | Cached recompressed SWU files |

Example:
```
/var/lib/adu/downloads/delta-cache/
├── adu-update-image-v1-recompressed.swu
└── adu-update-image-v2-recompressed.swu
```

## Differences from yocto-a-b-update.sh

| Feature | yocto-a-b-update.sh | microsoft-delta-source-caching.sh |
|---------|---------------------|-----------------------------------|
| Lines of code | ~1300 | ~450 |
| Partition detection | ✓ Complex U-Boot logic | ✗ Relies on SWUpdate |
| U-Boot management | ✓ Manages bootcount, upgrade flags | ✗ Minimal |
| Workflow blacklist | ✓ Failed workflow tracking | ✗ Simplified |
| Delta caching | ✗ Not supported | ✓ Automatic with verification |
| Cache verification | N/A | ✓ Mandatory |
| Error handling | Complex with many error codes | Simplified |
| Target audience | Production | Testing |

## Testing

### Manual Test

```bash
# 1. Check if installed
/usr/lib/adu/microsoft-delta-source-caching.sh \
  --is-installed 1.0.0 \
  --result-file /tmp/result.json

# 2. Install update
/usr/lib/adu/microsoft-delta-source-caching.sh \
  --install \
  --image-file /var/lib/adu/downloads/adu-update-image-v1.swu \
  --result-file /tmp/result.json

# 3. Check result
cat /tmp/result.json
cat /adu/logs/delta-source-caching.log

# 4. Verify delta cache
ls -la /var/lib/adu/downloads/delta-cache/
```

### Via ADU Agent

Deploy the update with the import manifest shown above. Monitor logs:

```bash
journalctl -u deviceupdate-agent -f
tail -f /adu/logs/delta-source-caching.log
```

## Troubleshooting

### Problem: Install fails with "delta source caching failed" (Error 0x30102004)

**Symptom:** Install action completes SWUpdate successfully but fails with error code 0x30102004

**Root Causes:**
1. The recompressed file was not included in the update package
2. Recompressed file has incorrect naming convention
3. Caching verification failed after copy
4. Insufficient permissions or disk space

**Diagnostic Steps:**
```bash
# Check if recompressed file exists in work folder
ls -l /var/lib/adu/downloads/work-folder/*-recompressed.swu

# Check delta cache directory
ls -l /var/lib/adu/downloads/delta-cache/

# Check disk space
df -h /var/lib/adu/

# Check permissions
ls -ld /var/lib/adu/downloads/delta-cache/
namei -l /var/lib/adu/downloads/delta-cache/

# Review handler logs
tail -50 /adu/logs/delta-source-caching.log
```

**Solutions:** 
1. **Verify import manifest includes recompressed file:**
   ```json
   "files": ["v1.0.swu", "v1.0-recompressed.swu"]
   ```

2. **Check naming convention:** Must be `<original_name>-recompressed.swu`
   ```
   Correct:   adu-update-image-v1.swu → adu-update-image-v1-recompressed.swu
   Incorrect: adu-update-image-v1.swu → v1-recompressed.swu
   Incorrect: adu-update-image-v1.swu → adu-update-image-v1.recompressed.swu
   ```

3. **Ensure file was downloaded to same directory as main SWU:**
   Both files must be in `/var/lib/adu/downloads/work-folder/` or work directory

4. **Check cache directory exists and is writable:**
   ```bash
   sudo mkdir -p /var/lib/adu/downloads/delta-cache
   sudo chown adu:adu /var/lib/adu/downloads/delta-cache
   sudo chmod 755 /var/lib/adu/downloads/delta-cache
   ```

5. **Verify disk space:** Need at least 800MB free for typical SWU cache
   ```bash
   df -h /var/lib/adu/ | grep -v "^Filesystem"
   ```

6. **Check SELinux/AppArmor:** May block file operations
   ```bash
   # SELinux
   getenforce
   sudo setenforce 0  # Temporarily disable for testing
   
   # AppArmor
   sudo aa-status
   ```

### Problem: Delta update fails with "source not found"

**Symptom:** Delta reconstruction fails because source SWU is missing from cache

**Root Causes:**
1. Previous update didn't cache the recompressed SWU
2. Cache was cleared or corrupted
3. Source version doesn't match manifest expectations
4. File hash mismatch between cache and manifest

**Diagnostic Steps:**
```bash
# Check what's in cache
ls -lR /var/lib/adu/downloads/delta-cache/

# Check current version
cat /etc/adu-version

# Review previous update logs
grep -i "cache\|recompressed" /adu/logs/*.log

# Check delta handler logs
sudo journalctl -u deviceupdate-agent | grep -i "delta.*source\|cache"
```

**Solutions:**
1. **Re-deploy the source version to populate cache:**
   ```bash
   # Deploy v1.0 (full update) again to cache v1.0-recompressed.swu
   # Then deploy v2.0 (delta update)
   ```

2. **Manually populate cache (for testing):**
   ```bash
   # Copy recompressed SWU from build output
   sudo cp ~/build-output/v1.0-recompressed.swu \
           /var/lib/adu/downloads/delta-cache/
   
   sudo chown adu:adu /var/lib/adu/downloads/delta-cache/*.swu
   ```

3. **Verify cache contents match expectations:**
   ```bash
   # Compute hash of cached file
   sha256sum /var/lib/adu/downloads/delta-cache/v1.0-recompressed.swu
   
   # Compare with manifest's microsoft.sourceFileHash
   # Must match exactly
   ```

4. **Check that handler cached file properly:**
   ```bash
   # Look for cache verification in logs
   grep "successfully cached\|cache.*verified" /adu/logs/delta-source-caching.log
   ```

### Problem: SWUpdate fails with "Invalid image" or "cannot open"

**Symptom:** SWUpdate execution fails when trying to install the update

**Root Causes:**
1. SWU file is corrupted
2. Wrong SWU file format (not recompressed with zstd)
3. SWUpdate not built with CONFIG_ZSTD=y
4. File permissions prevent reading

**Diagnostic Steps:**
```bash
# Verify SWU file integrity
file /var/lib/adu/downloads/work-folder/*.swu
cpio -itv < /var/lib/adu/downloads/work-folder/*.swu

# Check if using zstd compression
cpio -itv < /var/lib/adu/downloads/work-folder/*.swu 2>&1 | grep -i zstd

# Check SWUpdate capabilities
swupdate --help | grep -i zstd
swupdate --version

# Check file permissions
ls -l /var/lib/adu/downloads/work-folder/*.swu

# Manual test
sudo swupdate -i /var/lib/adu/downloads/work-folder/v1.0.swu -v
```

**Solutions:**
1. **Rebuild SWUpdate with zstd support:**
   ```bash
   # In Yocto local.conf or machine config
   SWUPDATE_EXTRA_FEATURES = "zstd"
   ```

2. **Verify recompression on build server:**
   ```bash
   # Check that adu-delta-image recipe ran successfully
   bitbake -c cleansstate adu-delta-image
   bitbake adu-delta-image -v
   
   # Verify output has zstd compression
   cpio -itv < tmp/deploy/images/*/v1.0-recompressed.swu 2>&1 | grep zstd
   ```

3. **Check file isn't corrupted:**
   ```bash
   # Compute hash and compare with manifest
   sha256sum /var/lib/adu/downloads/work-folder/*.swu
   ```

### Problem: Handler logs show "Result file not created"

**Symptom:** ADU agent reports handler failure but no result file exists

**Root Causes:**
1. `--result-file` argument not provided (should never happen with ADU agent)
2. Directory for result file doesn't exist or isn't writable
3. Handler crashed before writing result

**Diagnostic Steps:**
```bash
# Check handler arguments in agent logs
sudo journalctl -u deviceupdate-agent | grep -i "scriptFileName\|arguments"

# Check result file location
ls -l /adu/logs/delta-source-caching.result.json

# Check handler execution
ps aux | grep delta-source-caching
```

**Solutions:**
1. This should never happen with ADU agent - indicates agent configuration issue
2. Check ADU agent configuration: `/etc/adu/du-config.json`
3. Verify handler is properly installed: `ls -l /usr/lib/adu/microsoft-delta-source-caching.sh`

### Problem: Disk space exhausted during update

**Symptom:** Update fails with disk space errors

**Root Causes:**
1. Insufficient space for SWU files + cache
2. Previous cached files not cleaned up
3. Reconstruction creates large temporary files

**Required Space:**
- Work folder: 800MB (main SWU)
- Work folder: 800MB (recompressed SWU)
- Delta cache: 800MB (cached recompressed SWU)
- **Total: ~2.4GB minimum**

**Diagnostic Steps:**
```bash
# Check disk usage
df -h /var/lib/adu/
du -sh /var/lib/adu/downloads/*

# Check cache size
du -sh /var/lib/adu/downloads/delta-cache/
ls -lh /var/lib/adu/downloads/delta-cache/

# Check for old files
find /var/lib/adu/downloads/ -type f -name "*.swu" -mtime +7
```

**Solutions:**
1. **Clean old cached files:**
   ```bash
   # Remove old versions from cache (keep only latest)
   sudo rm /var/lib/adu/downloads/delta-cache/v0.*.swu
   ```

2. **Increase partition size:** Update device partition layout to allocate more space

3. **Use external storage:** Mount larger storage to `/var/lib/adu/`

4. **Implement cache cleanup policy:** Automatically remove oldest cached files when space is low

## See Also

- [yocto-a-b-update.sh](https://github.com/Azure/iot-hub-device-update/blob/main/src/extensions/step_handlers/swupdate_handler_v2/examples/yocto-a-b-update.sh) - Full-featured production handler
- [ADU Script Handler Documentation](https://learn.microsoft.com/en-us/azure/iot-hub-device-update/understand-device-update-configuration-file#script-handler)
- [Delta Updates Documentation](https://learn.microsoft.com/en-us/azure/iot-hub-device-update/device-update-delta-updates)
