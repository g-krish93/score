# Cricket Live Score Overlay

## Architecture

Phone 1 (Larix) streams with overlay URL `http://EC2-IP:5000`, Phone 3 updates score on `http://EC2-IP:5000/input`, and the Flask app serves `/score` for overlay polling while persisting backup to `/tmp/cricket_state.json`.

## First-time setup

1. Create EC2 key pair in AWS console (eu-west-2).
2. Set `key_name` and `github_repo` variables.
3. Run `cd infra && terraform init && terraform apply`.
4. Copy `.env.example` to `.env` and keep `PORT=5000`.
5. Upload `.env` to EC2 `/app/.env`.
6. Restart service and test `curl http://PUBLIC_IP:5000/health`.
7. Add `EC2_HOST` and `EC2_KEY` secrets in GitHub repository.

## Match day

1. Open input UI on Phone 3 at `/input` (or scoped match page like `/m/bmacc-team1/input`).
2. Configure match and start.
3. Load overlay `/` (or scoped overlay `/m/bmacc-team1`) in your stream Browser Source.
4. Score ball-by-ball and switch overlay panels as needed.

## Save/restore

- Auto-save runs after every `/ball`.
- Manual save: `POST /save`
- Manual restore: `POST /restore`

## Multiple parallel matches

- Use dedicated URLs per match so states do not mix.
- Example Team 1:
  - Input: `/m/bmacc-team1/input`
  - Overlay: `/m/bmacc-team1`
- Example Team 2:
  - Input: `/m/bmacc-team2/input`
  - Overlay: `/m/bmacc-team2`
- All API calls from those pages automatically include the match scope.

## SSH cheat sheet

- `sudo systemctl status cricket`
- `sudo journalctl -u cricket -f`
- `sudo systemctl restart cricket`
