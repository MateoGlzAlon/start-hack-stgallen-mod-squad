```bash
# 1. Service is up (no token needed) → 200
curl -s 'https://saw26api.ashyground-364e1d07.switzerlandnorth.azurecontainerapps.io/healthz' | jq
curl -s -o /dev/null -w 'HTTP %{http_code}\n' 'https://saw26api.ashyground-364e1d07.switzerlandnorth.azurecontainerapps.io/healthz'
```

# {"status":"ok","service":"saw26-sandbox","api_version":"0.1.0","pack_version":"saw26"}

```bash
# 2. My token on an authenticated endpoint → I get 401, I expected 200
curl -s 'https://leash-api-production.up.railway.app' \
  --header 'Authorization: Bearer leash_DcG3HL3qj472uRqIvnTjyVwpKfrRfDxN' | jq
```

# HTTP/2 401
# {"error":{"code":"unauthorized","message":"A valid team bearer token is required"}}