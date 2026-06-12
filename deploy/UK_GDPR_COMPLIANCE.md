# CricRelay — UK GDPR / Data Protection Act 2018 posture

Practical mapping of what the service does to UK data-protection obligations. Not legal
advice — a working checklist for the operator.

## Personal data we process
- Account: name, email, hashed password (`cricrelay_org`, `cricrelay_user`).
- Club membership + role; admins can see members in their club.
- Encrypted YouTube/Twitch OAuth refresh tokens.
- Stream records: who streamed, duration, viewer stats, VOD link, final score (`cricrelay_stream_session`).
- Sponsors (business info, not personal).
- Mobile only: Firebase Crashlytics/Analytics (disclosed in the policy).

## Controls in place

| Obligation (UK GDPR) | How it's met | Where |
|---|---|---|
| **Lawful basis / consent** (Art. 6) | Consent captured at registration with timestamp | `consent_given_at`; `/register` |
| **Transparency** (Art. 13) | Privacy policy lists all categories incl. recordings, viewer stats, member attribution, storage location | `templates/privacy.html` |
| **Right of access / portability** (Art. 15, 20) | `GET /api/auth/account/export` returns account, users, streams, sessions as JSON | `app.py api_export_account` |
| **Right to erasure** (Art. 17) | `DELETE /api/auth/account` cascades org, users, sponsors, stream sessions, matches, revokes OAuth | `app.py api_delete_account` |
| **Rectification** (Art. 16) | Editable via dashboard (branding/name) and account | dashboard |
| **Security of processing** (Art. 32) | Hashed passwords, Fernet-encrypted OAuth tokens, HTTPS + HSTS/CSP headers, rate-limited auth, encrypted backups | `deploy/aws-security-hardening.md`, nginx conf |
| **Resilience / restore** (Art. 32(1)(c)) | Daily encrypted EBS snapshots + nightly S3 SQLite dumps, documented restore | `infra/backups.tf`, `deploy/BACKUP_RESTORE_RUNBOOK.md` |
| **Data residency** | All storage + backups in eu-west-2 (UK) | `infra/variables.tf` region |
| **Storage limitation** (Art. 5(1)(e)) | Data kept until account deletion; backups expire after 30 days | lifecycle rule, policy |

## Open items to close (operator actions)
1. **ICO registration / data protection fee** — a UK controller processing personal data
   electronically generally must pay the ICO fee and register. Confirm status at ico.org.uk.
2. **Name a real controller** — the policy says "the CricRelay team." Put a real legal
   entity/person + contact once you trade. Consider whether a DPO is needed (likely not at this scale).
3. **ROPA** — keep a short Record of Processing Activities (Art. 30). This file is a starting point.
4. **Breach process** (Art. 33) — UK GDPR requires notifying the ICO within 72h of a qualifying
   breach. Write a one-page who-does-what; the restore runbook covers recovery.
5. **At-rest volume encryption** — verify/enable per `deploy/ebs-encryption.md`.
6. **Backups vs erasure** — backups retain deleted data up to 30 days. The policy now discloses
   this; ensure you never restore a backup to "undelete" an erased account outside disaster recovery.
7. **Sub-processors** — YouTube, Twitch, Firebase (mobile), AWS. List them if you publish a
   sub-processor page; all are disclosed in the policy except AWS (hosting) — consider adding.
8. **SSH still open to 0.0.0.0/0** — tracked in `deploy/aws-security-hardening.md`; move to SSM
   to reduce attack surface (defence in depth, not strictly a GDPR item).

## Quick verification
```bash
# Backups exist and are encrypted (see BACKUP_RESTORE_RUNBOOK.md)
# Security headers present:
curl -sI https://cricrelay.co.uk/ | grep -iE 'strict-transport|content-security|x-frame|referrer-policy'
# Erasure cascades the new tables (local test):
#   register -> create sponsor/session -> DELETE /api/auth/account -> rows gone
```
