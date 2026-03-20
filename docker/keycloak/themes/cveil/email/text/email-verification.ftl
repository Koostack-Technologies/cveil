<#ftl output_format="plainText">
<#assign verifyLink = link>
<#if link?contains("key=")>
<#assign keyParam = link?keep_after("key=")>
<#if keyParam?contains("&")>
<#assign keyParam = keyParam?keep_before("&")>
</#if>
<#assign verifyLink = "${properties.appBaseUrl}/verify-email/callback?key=${keyParam}">
</#if>
Cveil — Verify Your Email

Hi ${user.firstName!"there"},

Please verify your email address to activate your Cveil account.

Click the link below to verify:

${verifyLink}

This link will expire in <#if (linkExpiration &gt; 119)>${(linkExpiration / 60)?floor} hours<#else>${linkExpiration} minutes</#if>.

If you did not request this, you can safely ignore this email.

— The Cveil Team
https://cveil.io
