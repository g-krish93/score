# EBS at-rest encryption — verify & enable

Personal data sits on the instance's root EBS volume. UK GDPR Art. 32 expects encryption
at rest where appropriate. Two parts: **default encryption** (easy, non-destructive) and
**the existing root volume** (needs a swap with brief downtime).

## 1. Verify current state

```bash
# Is account/region default EBS encryption already on?
aws ec2 get-ebs-encryption-by-default --region eu-west-2
# -> {"EbsEncryptionByDefault": true|false}

# Is the live root volume encrypted?
aws ec2 describe-volumes --region eu-west-2 \
  --filters "Name=tag:Backup,Values=cricrelay" \
  --query 'Volumes[].{Id:VolumeId,Encrypted:Encrypted,State:State}' --output table
```

- If **default = true** and the volume shows **Encrypted = true** → you're done; nothing else needed.
- If **default = false** → `terraform apply` now sets it (resource `aws_ebs_encryption_by_default`
  in `infra/backups.tf`). This makes all **new** volumes and **all DLM snapshots** encrypted.
  It does **not** retroactively encrypt the existing root volume.

## 2. Encrypt the existing root volume (only if it shows Encrypted = false)

You cannot encrypt a volume in place — snapshot it, copy the snapshot with encryption, make a
new volume, and swap. ~10–20 min, one short stop of the instance. **Do in a maintenance window.**

```bash
REGION=eu-west-2
INSTANCE_ID=$(aws ec2 describe-instances --region $REGION \
  --filters "Name=tag:Name,Values=cricket-overlay" \
  --query 'Reservations[].Instances[].InstanceId' --output text)
VOL_ID=$(aws ec2 describe-instances --region $REGION --instance-ids $INSTANCE_ID \
  --query 'Reservations[].Instances[].BlockDeviceMappings[0].Ebs.VolumeId' --output text)
AZ=$(aws ec2 describe-instances --region $REGION --instance-ids $INSTANCE_ID \
  --query 'Reservations[].Instances[].Placement.AvailabilityZone' --output text)

# 1) Snapshot current (unencrypted) volume
SNAP=$(aws ec2 create-snapshot --region $REGION --volume-id $VOL_ID \
  --description "pre-encryption cricrelay" --query SnapshotId --output text)
aws ec2 wait snapshot-completed --region $REGION --snapshot-ids $SNAP

# 2) Encrypted copy of the snapshot
ENC_SNAP=$(aws ec2 copy-snapshot --region $REGION --source-region $REGION \
  --source-snapshot-id $SNAP --encrypted --description "encrypted cricrelay" \
  --query SnapshotId --output text)
aws ec2 wait snapshot-completed --region $REGION --snapshot-ids $ENC_SNAP

# 3) New encrypted volume from it
NEW_VOL=$(aws ec2 create-volume --region $REGION --availability-zone $AZ \
  --snapshot-id $ENC_SNAP --volume-type gp3 \
  --tag-specifications 'ResourceType=volume,Tags=[{Key=Backup,Value=cricrelay}]' \
  --query VolumeId --output text)
aws ec2 wait volume-available --region $REGION --volume-ids $NEW_VOL

# 4) Swap: stop, detach old, attach new as /dev/xvda, start
aws ec2 stop-instances --region $REGION --instance-ids $INSTANCE_ID
aws ec2 wait instance-stopped --region $REGION --instance-ids $INSTANCE_ID
aws ec2 detach-volume --region $REGION --volume-id $VOL_ID
aws ec2 wait volume-available --region $REGION --volume-ids $VOL_ID
aws ec2 attach-volume --region $REGION --volume-id $NEW_VOL --instance-id $INSTANCE_ID --device /dev/xvda
aws ec2 start-instances --region $REGION --instance-ids $INSTANCE_ID
```

After it boots: `curl -s http://<EIP>:5000/health`, confirm logins + a YouTube/Twitch status
work, then delete the old unencrypted volume and the **unencrypted** snapshot `$SNAP`.

> Reconcile Terraform afterwards: the root volume id changes. Either `terraform apply`
> (may want to recreate — review the plan carefully) or `terraform state` import the new
> volume. Easiest long-term: with default encryption on, the **next** instance rebuild from
> AMI is encrypted with no special steps.
