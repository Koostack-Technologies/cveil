<#macro emailLayout>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="X-UA-Compatible" content="IE=edge">
  <title>Cveil</title>
  <!--[if mso]>
  <noscript>
    <xml>
      <o:OfficeDocumentSettings>
        <o:PixelsPerInch>96</o:PixelsPerInch>
      </o:OfficeDocumentSettings>
    </xml>
  </noscript>
  <![endif]-->
</head>
<body style="margin:0;padding:0;background-color:#0b1326;font-family:'Inter','Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;-webkit-font-smoothing:antialiased;">

  <!-- Outer wrapper -->
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#0b1326;min-height:100vh;">
    <tr>
      <td align="center" style="padding:40px 16px;">

        <!-- Inner card -->
        <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;background-color:#131b2e;border-radius:8px;">

          <!-- Logo -->
          <tr>
            <td align="center" style="padding:40px 40px 24px 40px;">
              <table role="presentation" cellpadding="0" cellspacing="0">
                <tr>
                  <td style="background:linear-gradient(135deg,#4fdbc8,#14b8a6);width:44px;height:44px;border-radius:8px;text-align:center;vertical-align:middle;font-size:22px;font-weight:700;color:#0b1326;font-family:'Manrope','Inter',sans-serif;">
                    C
                  </td>
                  <td style="padding-left:12px;font-family:'Manrope','Inter',sans-serif;font-size:22px;font-weight:700;color:#e8eaed;letter-spacing:-0.02em;">
                    Cveil
                  </td>
                </tr>
              </table>
            </td>
          </tr>

          <!-- Content -->
          <tr>
            <td style="padding:0 40px 40px 40px;">
              <#nested>
            </td>
          </tr>

          <!-- Footer -->
          <tr>
            <td style="padding:0 40px 32px 40px;">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
                <tr>
                  <td style="border-top:1px solid #222a3d;padding-top:24px;">
                    <p style="margin:0;font-size:12px;line-height:18px;color:#8b9faf;">
                      This email was sent by Cveil. If you did not request this action, you can safely ignore this email.
                    </p>
                    <p style="margin:12px 0 0 0;font-size:12px;line-height:18px;color:#8b9faf;">
                      &copy; ${.now?string("yyyy")} Cveil Inc. &mdash; Enterprise Consent &amp; Data Masking
                    </p>
                  </td>
                </tr>
              </table>
            </td>
          </tr>

        </table>

      </td>
    </tr>
  </table>

</body>
</html>
</#macro>
<@emailLayout>

  <#-- Rewrite the link to point to our callback when this is a verify-email action -->
  <#assign actionLink = link>
  <#if requiredActions?seq_contains("VERIFY_EMAIL") && link?contains("key=")>
    <#assign keyParam = link?keep_after("key=")>
    <#if keyParam?contains("&")>
      <#assign keyParam = keyParam?keep_before("&")>
    </#if>
    <#assign actionLink = "${properties.appBaseUrl}/verify-email/callback?key=${keyParam}">
  </#if>

  <#-- Heading -->
  <h1 style="margin:0 0 8px 0;font-family:'Manrope','Inter',sans-serif;font-size:24px;font-weight:700;color:#e8eaed;line-height:32px;">
    <#if requiredActions?seq_contains("VERIFY_EMAIL")>
      Verify Your Email
    <#elseif requiredActions?seq_contains("UPDATE_PASSWORD")>
      Set Your Password
    <#else>
      Action Required
    </#if>
  </h1>

  <p style="margin:0 0 24px 0;font-size:15px;line-height:24px;color:#8b9faf;">
    Hi ${user.firstName!"there"}, please complete the following to activate your account:
  </p>

  <#-- Action list -->
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom:28px;">
    <#if requiredActions??>
      <#list requiredActions as reqActionItem>
        <tr>
          <td style="padding:8px 0;">
            <table role="presentation" cellpadding="0" cellspacing="0">
              <tr>
                <td style="width:24px;height:24px;background-color:rgba(79,219,200,0.1);border-radius:50%;text-align:center;vertical-align:middle;">
                  <span style="color:#4fdbc8;font-size:13px;font-weight:700;">${reqActionItem?counter}</span>
                </td>
                <td style="padding-left:12px;font-size:14px;color:#e8eaed;font-weight:500;">
                  <#if reqActionItem == "VERIFY_EMAIL">Verify your email address</#if>
                  <#if reqActionItem == "UPDATE_PASSWORD">Set your password</#if>
                  <#if reqActionItem == "CONFIGURE_TOTP">Set up two-factor authentication</#if>
                  <#if reqActionItem == "UPDATE_PROFILE">Update your profile</#if>
                </td>
              </tr>
            </table>
          </td>
        </tr>
      </#list>
    </#if>
  </table>

  <#-- CTA Button -->
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-bottom:24px;">
    <tr>
      <td align="center">
        <!--[if mso]>
        <v:roundrect xmlns:v="urn:schemas-microsoft-com:vml" xmlns:w="urn:schemas-microsoft-com:office:word"
          href="${actionLink}" style="height:48px;v-text-anchor:middle;width:280px;" arcsize="13%" fillcolor="#14b8a6">
          <w:anchorlock/>
          <center style="color:#0b1326;font-family:'Inter',sans-serif;font-size:15px;font-weight:600;">
            Complete Action &rarr;
          </center>
        </v:roundrect>
        <![endif]-->
        <!--[if !mso]><!-->
        <a href="${actionLink}"
           style="display:inline-block;padding:12px 40px;background:linear-gradient(135deg,#4fdbc8,#14b8a6);color:#0b1326;font-size:15px;font-weight:600;text-decoration:none;border-radius:6px;font-family:'Inter',sans-serif;letter-spacing:0.01em;">
          Complete Action &rarr;
        </a>
        <!--<![endif]-->
      </td>
    </tr>
  </table>

  <#-- Link expiration -->
  <p style="margin:0;font-size:13px;line-height:20px;color:#8b9faf;text-align:center;">
    This link expires in <strong style="color:#e8eaed;"><#if (linkExpiration &gt; 119)>${(linkExpiration / 60)?floor} hours<#else>${linkExpiration} minutes</#if></strong>.
  </p>

  <#-- Fallback URL -->
  <p style="margin:16px 0 0 0;font-size:12px;line-height:18px;color:#8b9faf;word-break:break-all;">
    If the button doesn't work, copy and paste this URL into your browser:<br/>
    <a href="${actionLink}" style="color:#4fdbc8;text-decoration:underline;">${actionLink}</a>
  </p>

</@emailLayout>
