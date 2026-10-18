# Remove only the inspected six-line watchdog; preserve all unrelated shell code.
{
    lines[NR] = $0
}
END {
    for (line = 1; line <= NR; line++) {
        if (lines[line] == "(" && lines[line + 1] == "while [ 1 ]; do" &&
            lines[line + 2] ~ /^[ \t]*\/system\/bin\/sh \/data\/local\/tmp\/load[.]bin[ \t]*$/ &&
            lines[line + 3] ~ /^[ \t]*sleep 2[ \t]*$/ &&
            lines[line + 4] == "done" && lines[line + 5] == ") &") {
            line += 5
            continue
        }
        if (lines[line] ~ /^[ \t]*chmod \+x \/data\/local\/tmp\/load[.]bin[ \t]*$/) continue
        if (tolower(lines[line]) ~ /voyahtweaks|\/data\/local\/tmp\/(load|launcher|ss11|ss9)[.]bin/) exit 1
        print lines[line]
    }
}
