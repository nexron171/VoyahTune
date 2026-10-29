package main

import "voyahtune/unified-loader/resident"

const updateBlockPath = "/data/local/bin/voyahtune-update.block"
const migrationBootPath = "/data/local/bin/voyahtune-native-migration.boot"

func admissionBlock(updatePath, migrationPath, boot string) error {
	return resident.CheckAdmission(updatePath, migrationPath, boot)
}
