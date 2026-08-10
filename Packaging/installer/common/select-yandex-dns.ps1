param(
    [ValidateSet('on', 'off', 'external', 'broken')]
    [string]$Current = 'off',
    [string]$HashPath = ''
)

# The same pinned helper performs the Windows preflight hash without another script dependency.
if ($HashPath) {
    [Console]::Out.WriteLine((Get-FileHash -LiteralPath $HashPath -Algorithm SHA256).Hash.ToLowerInvariant())
    exit 0
}

# Exit codes are the machine-readable result: 10=on, 11=off, 12=keep.
# This keeps stdin attached to the real console so ReadKey receives arrow keys reliably.
if ($Current -eq 'external') {
    [Console]::Error.WriteLine('An external DNS overlay was found; it will be left unchanged.')
    exit 12
}
if ($Current -eq 'broken') {
    [Console]::Error.WriteLine('DNS overlay state is ambiguous; it will be left unchanged.')
    exit 12
}
if ([Console]::IsInputRedirected) {
    [Console]::Error.WriteLine('Non-interactive run: DNS overlay state is unchanged.')
    exit 12
}

$selected = if ($Current -eq 'on') { 'on' } else { 'off' }
$oldCursorVisible = [Console]::CursorVisible
[Console]::CursorVisible = $false
try {
    while ($true) {
        $line = if ($selected -eq 'on') {
            'Use Yandex DNS?  [Yes]   No    (arrows, Enter)'
        } else {
            'Use Yandex DNS?   Yes   [No]   (arrows, Enter)'
        }
        [Console]::Error.Write("`r{0}" -f $line.PadRight(70))
        $key = [Console]::ReadKey($true)
        switch ($key.Key) {
            'UpArrow'    { $selected = 'on' }
            'LeftArrow'  { $selected = 'on' }
            'DownArrow'  { $selected = 'off' }
            'RightArrow' { $selected = 'off' }
            'Y'          { $selected = 'on'; break }
            'N'          { $selected = 'off'; break }
            'Enter'      { break }
        }
        if ($key.Key -in @('Y', 'N', 'Enter')) { break }
    }
} finally {
    [Console]::CursorVisible = $oldCursorVisible
    [Console]::Error.WriteLine()
}

if ($selected -eq 'on') { exit 10 }
exit 11
