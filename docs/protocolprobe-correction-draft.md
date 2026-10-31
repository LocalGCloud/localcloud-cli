# Correction request draft — not sent

To: hello@protocolprobe.com

Subject: Recheck request: cloud.local/localcloud v0.1.12 bundle acquisition

Hello ProtocolProbe team,

We maintain LocalCloud. Your [October 23 record](https://protocolprobe.com/mcp/servers/cloud.local/localcloud)
reports `bundle-unavailable` before a runtime session, with no runtime checks
exercised and the cause undetermined.

On October 31 we downloaded the exact registry artifact successfully (HTTP 200,
75,828,472 bytes). Its SHA-256 is
`ecf34a2f6d5ea57c6dd742970e78ab3285ba519409402af1e3d48cea99c29722`,
matching the registry and release checksum. All 530 ZIP members pass CRC and
extraction, and the official MCPB 2.1.2 manifest validator passes. See our
[dated evidence and reproduction commands](protocolprobe-evidence-2026-10-31.md).

Could you recheck acquisition of this exact artifact and share the original
failed substage, sanitized HTTP/download/hash/archive error, and compressed or
expanded size limits? Today's result does not establish what failed on October
23, and we are not asking you to mark any unrun security check as passed.
Please distinguish an undetermined bundle-acquisition failure from a confirmed
runtime startup defect in the current page wording.

We have also prepared a root SECURITY.md pointing to the existing
https://local.cloud/security/ reporting policy. It is not published yet; please
retain the historical October 23 absence finding. A fresh policy check should
wait until the file is publicly available on the default branch.

Thank you,
LocalCloud maintainers

Before sending: publish the approved evidence/policy changes and replace the
relative evidence link with its public permalink. This draft makes no claim
that the historical cause has been determined or that runtime security passed.
