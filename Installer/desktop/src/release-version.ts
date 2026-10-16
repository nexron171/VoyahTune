function parseReleaseVersion(version: string) {
  const normalized = version.split("+")[0].replace(/-(od|pi)$/, "");
  const match = /^(\d+)\.(\d+)\.(\d+)(?:-(.+))?$/.exec(normalized);
  if (!match) return null;
  return {
    numbers: match.slice(1, 4).map(Number),
    prerelease: match[4]?.split(".") ?? [],
  };
}

export function compareReleaseVersionsNewestFirst(left: string, right: string): number {
  const leftVersion = parseReleaseVersion(left);
  const rightVersion = parseReleaseVersion(right);
  if (!leftVersion || !rightVersion) {
    if (leftVersion) return -1;
    if (rightVersion) return 1;
    return right.localeCompare(left, "en", { numeric: true });
  }

  for (let index = 0; index < 3; index++) {
    const difference = rightVersion.numbers[index] - leftVersion.numbers[index];
    if (difference) return difference;
  }

  const leftPrerelease = leftVersion.prerelease;
  const rightPrerelease = rightVersion.prerelease;
  if (!leftPrerelease.length || !rightPrerelease.length) {
    return Number(!!leftPrerelease.length) - Number(!!rightPrerelease.length);
  }

  for (let index = 0; index < Math.max(leftPrerelease.length, rightPrerelease.length); index++) {
    const leftPart = leftPrerelease[index];
    const rightPart = rightPrerelease[index];
    if (leftPart === rightPart) continue;
    if (leftPart === undefined) return 1;
    if (rightPart === undefined) return -1;

    const leftNumeric = /^\d+$/.test(leftPart);
    const rightNumeric = /^\d+$/.test(rightPart);
    if (leftNumeric && rightNumeric) return Number(rightPart) - Number(leftPart);
    if (leftNumeric !== rightNumeric) return leftNumeric ? 1 : -1;
    return leftPart < rightPart ? 1 : -1;
  }
  return 0;
}
