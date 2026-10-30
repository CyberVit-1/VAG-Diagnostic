# Recorded OEM regression fixtures

`vehicle-033-oem.tsv`: OEM section of the 2026-10-29 07:09 exported log (578 commands).
`vehicle-034-oem.tsv`: OEM section of the 2026-10-29 09:56 exported log (601 commands).

Columns: exact command, then base64-encoded raw reply without the ELM prompt. The test stream appends the mandatory prompt and supplies bytes individually. VIN requests, timestamps, adapter address, and non-wire UI events are excluded. No delay measurements are reproduced. Therefore a replay cannot validate real adapter timing or another transport.

The 034 metadata test explicitly substitutes new TP request 22F187 / synthetic NRC31 for recorded 1A9B / NRC11; all other recorded exchanges stay unchanged. This substitution is software coverage only. The original resource remains unchanged. No new vehicle outcome is claimed.

Current replay adaptation also omits entire historical UDS setup/probe blocks for 70D/777 and718/782 only when every application reply is recorded NO DATA. The original TSV files remain unchanged. Any actual payload or unknown request in such a block rejects the adaptation. Current fixed routes use46=17FC008B/17FE008B and56=TP-only; genuine bitmap mappings may supply their own confirmed route. Omitting an unsuccessful historical probe is software adaptation, not a vehicle confirmation.
