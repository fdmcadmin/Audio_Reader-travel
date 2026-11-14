param([Parameter(Mandatory)][string]$InputFile, [Parameter(Mandatory)][string]$OutputFile, [string]$ProfileSetId)
$ErrorActionPreference = 'Stop'
$inputItem = Get-Item -LiteralPath $InputFile
if ($inputItem.Length -gt 2MB) { throw 'Collection module limit is 2 MB.' }
$target = [IO.Path]::GetFullPath($OutputFile)
if (Test-Path -LiteralPath $target) { throw 'Choose a new output file; existing collection files are preserved.' }
$module = [IO.File]::ReadAllText($inputItem.FullName) | ConvertFrom-Json
$characters = @($module.entries | Where-Object { $_.id -like 'char_*' -or $_.category -eq 'Characters' -or $_.displayName -match '^\d+\s+.+[—–-]\s*Preset$' })
if ($characters.Count -lt 1 -or $characters.Count -gt 64) { throw 'Export 1–64 character profiles.' }
if (-not $ProfileSetId) {
    $bytes = [Text.Encoding]::UTF8.GetBytes((@($characters | ForEach-Object { $_.id } | Sort-Object) -join "`n"))
    $hash = [Security.Cryptography.SHA256]::HashData($bytes)
    $ProfileSetId = 'profiles-' + [Convert]::ToHexString($hash).ToLowerInvariant().Substring(0,24)
}
$profiles = [ordered]@{ format='roadleaf-scene-profiles'; version=1; profileSetId=$ProfileSetId; entries=@($characters | ForEach-Object {
    $source = $_
    $entry = [ordered]@{ id=$source.id; displayName=$source.displayName; category='Characters'; enabled=[bool]$source.enabled; keys=@($source.keys) }
    foreach ($field in @('voiceService','voice','pronoun','delivery')) {
        if ($source.PSObject.Properties.Name -contains $field) { $entry[$field] = [string]$source.$field }
    }
    if ($source.ttsProfile) {
        $tag = $source.ttsProfile
        $entry['ttsProfile'] = [ordered]@{ service=[string]$tag.service; model=[string]$tag.model; voice=[string]$tag.voice; sid=[int]$tag.sid }
    }
    $entry
}) }
foreach ($field in @('voiceService','enginePackage','mainCharacter','mainVoice','mainPronoun','narratorVoice')) {
    if ($module.PSObject.Properties.Name -contains $field) { $profiles[$field] = [string]$module.$field }
}
[IO.File]::WriteAllText($target, ($profiles | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
[pscustomobject]@{ file=$target; profileSetId=$ProfileSetId; characters=$characters.Count; containsModuleText=$false; autoLoad=$false } | ConvertTo-Json
