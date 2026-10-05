# ASCII-only. Read the GitHub push-chain token from the Windows Credential Manager.
# Prints ONLY the token value to stdout so the caller can capture it into a variable /
# process env — it is never written to a file and never logged.
#
# Credential targets (in priority order):
#   'GitHub - https://api.github.com/Qianzln'  (repo-scoped app token: repo,user,workflow)
#   'git:https://github.com'                     (fallback git credential)
$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class DWTok {
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
  public struct CREDENTIAL {
    public UInt32 Flags; public UInt32 Type; public IntPtr TargetName; public IntPtr Comment;
    public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
    public UInt32 CredentialBlobSize; public IntPtr CredentialBlob;
    public UInt32 Persist; public UInt32 AttributeCount; public IntPtr Attributes;
    public IntPtr TargetAlias; public IntPtr UserName;
  }
  [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  public static extern bool CredRead(string target, UInt32 type, UInt32 flags, out IntPtr credential);
  [DllImport("advapi32.dll")]
  public static extern void CredFree(IntPtr cred);
}
"@

function Get-GlobalToken([string]$target) {
  $ptr = [IntPtr]::Zero
  if (-not [DWTok]::CredRead($target, 1, 0, [ref]$ptr)) { return $null }
  try {
    $c = [System.Runtime.InteropServices.Marshal]::PtrToStructure($ptr, [type][DWTok+CREDENTIAL])
    $size = [int]$c.CredentialBlobSize
    if ($size -le 0) { return $null }
    $bytes = New-Object byte[] $size
    [System.Runtime.InteropServices.Marshal]::Copy($c.CredentialBlob, $bytes, 0, $size)
    $text = [System.Text.Encoding]::UTF8.GetString($bytes) + "`n" + [System.Text.Encoding]::Unicode.GetString($bytes)
    # git credential-manager blobs look like "username=Qianzln`npassword=ghp_..."
    $pw = [regex]::Match($text, '(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})')
    if ($pw.Success) { return $pw.Value }
    return $null
  } finally { [DWTok]::CredFree($ptr) }
}

$targets = @(
  'GitHub - https://api.github.com/Qianzln',
  'git:https://github.com'
)
foreach ($t in $targets) {
  $tok = Get-GlobalToken $t
  if ($tok) { Write-Output $tok; break }
}
exit 0
