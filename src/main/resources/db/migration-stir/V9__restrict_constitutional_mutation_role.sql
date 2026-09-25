-- Administrative read access is not authority to rotate constitutional credentials.
REVOKE UPDATE (next_sequence,guardian_credential_id,guardian_public_key,guardian_status)
 ON stir.constitutional_authority FROM idax_admin;
REVOKE UPDATE (controller_id,credential_id,public_key,status)
 ON stir.constitutional_seat FROM idax_admin;
