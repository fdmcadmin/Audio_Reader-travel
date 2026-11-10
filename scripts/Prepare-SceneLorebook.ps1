param(
    [Parameter(Mandatory)][string]$InputFile,
    [Parameter(Mandatory)][string]$OutputFile,
    [switch]$ExplicitCast
)
$ErrorActionPreference = 'Stop'
$sourceFile = Get-Item -LiteralPath $InputFile
if ($sourceFile.Length -gt 2MB) { throw 'Lorebook limit is 2 MB.' }
$target = [IO.Path]::GetFullPath($OutputFile)
if ($sourceFile.FullName -eq $target) { throw 'Use a new output file; the source lorebook is preserved.' }
if (Test-Path -LiteralPath $target) { throw 'Choose a new output filename; an existing file will not be overwritten.' }
$book = [IO.File]::ReadAllText($sourceFile.FullName) | ConvertFrom-Json
if (@($book.entries).Count -gt 512) { throw 'Keep the lorebook to 512 entries.' }
$marker = '(?:^|\n)[ \t]*(?:\*(?:[ \t]*\*){2,}|-{3,}|_{3,}|⁂|#{3,}|\[Scene[^\]\n]{0,1600}\]|(?:Scene|Chapter)[ \t]+(?:[0-9]+|[IVXLCDM]+)[^\n]{0,120})[ \t]*(?:\n|$)'
function Escape-LiteralKey([string]$value) {
    [regex]::Escape($value).Replace('\ ', ' ').Replace('\#', '#').Replace('/', '\/')
}
foreach ($entry in $book.entries) {
    $keys = @($entry.keys | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    if ($keys.Count -eq 0) {
        # Global campaign/state entries are not character profiles. Preserve their activation.
        $entry.contextConfig.tokenBudget = 768
        continue
    }
    if (@($keys | Where-Object { $_.StartsWith('/') }).Count -gt 0) { throw 'This converter requires literal activation keys; no source regex is executed.' }
    $alternatives = ($keys | Sort-Object Length -Descending | ForEach-Object { Escape-LiteralKey $_ }) -join '|'
    $tail = "(?!(?:[\s\S]*$marker))[\s\S]*$"
    $pattern = if ($ExplicitCast) {
        '(?:^|\n)[ \t]*\[Scene(?:[ \t]+cast)?[ \t]*:[^\]\n]*(?<![\p{L}\p{N}_])(?:' + $alternatives + ')(?![\p{L}\p{N}_])[^\]\n]*\][ \t]*(?:\n|$)' + $tail
    } else { '(?<![\p{L}\p{N}_])(?:' + $alternatives + ')(?![\p{L}\p{N}_])' + $tail }
    $entry.keys = @('/' + $pattern + '/iu')
    $entry.forceActivation = $false
    $entry.nonStoryActivatable = $false
    $entry.searchRange = 10000
    $entry.contextConfig.tokenBudget = if ($entry.id -like 'char_*') { 768 } else { 384 }
    $entry.contextConfig.reservedTokens = 0
}
$book.order = @($book.entries | ForEach-Object { $_.id })
$result = $book | ConvertTo-Json -Depth 100
[IO.File]::WriteAllText($target, $result, [Text.UTF8Encoding]::new($false))
$verified = [IO.File]::ReadAllText($target) | ConvertFrom-Json
$original = [IO.File]::ReadAllText($sourceFile.FullName) | ConvertFrom-Json
for ($i=0; $i -lt @($original.entries).Count; $i++) {
    if ($verified.entries[$i].id -cne $original.entries[$i].id -or $verified.entries[$i].text -cne $original.entries[$i].text) { throw 'Profile text/identity changed; inspect the output before using it.' }
}
[pscustomobject]@{file=$target; entries=@($verified.entries).Count; explicitSceneCast=[bool]$ExplicitCast; originalTextPreserved=$true; maximumSearchCharacters=10000} | ConvertTo-Json
