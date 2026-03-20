<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Cveil — Verifying</title>
    <style>
        *, *::before, *::after { margin: 0; padding: 0; box-sizing: border-box; }
        html, body {
            min-height: 100vh;
            background-color: #0b1326;
            font-family: 'Inter', 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
            -webkit-font-smoothing: antialiased;
            color: #e8eaed;
        }
        body {
            display: flex;
            align-items: center;
            justify-content: center;
        }
        .container {
            text-align: center;
            max-width: 28rem;
            padding: 2rem 1rem;
        }
        .logo {
            display: inline-flex;
            align-items: center;
            gap: 0.75rem;
            margin-bottom: 2.5rem;
        }
        .logo-icon {
            width: 44px;
            height: 44px;
            border-radius: 8px;
            background: linear-gradient(135deg, #4fdbc8, #14b8a6);
            display: flex;
            align-items: center;
            justify-content: center;
            font-family: 'Manrope', 'Inter', sans-serif;
            font-size: 22px;
            font-weight: 700;
            color: #0b1326;
        }
        .logo-text {
            font-family: 'Manrope', 'Inter', sans-serif;
            font-size: 22px;
            font-weight: 700;
            color: #e8eaed;
            letter-spacing: -0.02em;
        }
        .spinner {
            width: 40px;
            height: 40px;
            margin: 0 auto 1.5rem auto;
            border: 3px solid rgba(79, 219, 200, 0.15);
            border-top-color: #4fdbc8;
            border-radius: 50%;
            animation: spin 0.8s linear infinite;
        }
        @keyframes spin {
            to { transform: rotate(360deg); }
        }
        h1 {
            font-family: 'Manrope', 'Inter', sans-serif;
            font-size: 1.5rem;
            font-weight: 700;
            margin-bottom: 0.5rem;
            color: #e8eaed;
        }
        .subtitle {
            font-size: 0.9375rem;
            color: #8b9faf;
            line-height: 1.5;
        }
        .redirect-link {
            display: inline-block;
            margin-top: 2rem;
            font-size: 0.8125rem;
            color: #4fdbc8;
            text-decoration: none;
        }
        .redirect-link:hover {
            color: #14b8a6;
        }
    </style>
</head>
<body>
    <div class="container">

        <div class="logo">
            <div class="logo-icon">C</div>
            <span class="logo-text">Cveil</span>
        </div>

        <div class="spinner"></div>

        <h1>${message.summary!""}</h1>
        <p class="subtitle">
            You will be redirected automatically. Please wait&hellip;
        </p>

        <#if pageRedirectUri?has_content>
            <a class="redirect-link" href="${pageRedirectUri}">Continue &rarr;</a>
        <#else>
            <a class="redirect-link" href="http://localhost:8080/verify-email/callback">Continue &rarr;</a>
        </#if>
    </div>

    <script>
        // Redirect to our own callback which verifies email via admin API.
        // Keycloak's verify-email action token requires a browser session that
        // ROPC-based apps don't have, so we handle verification server-side.
        (function() {
            var key = new URLSearchParams(window.location.search).get('key');
            if (!key) { return; }

            // Decode the JWT payload to extract the redirect URI (reduri claim)
            try {
                var parts = key.split('.');
                var payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
                var callbackUrl = payload.reduri || 'http://localhost:8080/verify-email/callback';
                // Append the key so our backend can extract the user ID and verify
                callbackUrl += (callbackUrl.indexOf('?') >= 0 ? '&' : '?') + 'key=' + encodeURIComponent(key);
                setTimeout(function() {
                    window.location.href = callbackUrl;
                }, 1500);
            } catch (e) {
                // Fallback: redirect without key
                setTimeout(function() {
                    window.location.href = 'http://localhost:8080/verify-email/callback';
                }, 1500);
            }
        })();
    </script>
</body>
</html>
