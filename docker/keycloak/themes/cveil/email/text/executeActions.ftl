<#outputformat "plainText">
<#assign actionLink = link>
<#if requiredActions?? && requiredActions?seq_contains("VERIFY_EMAIL") && link?contains("key=")>
<#assign keyParam = link?keep_after("key=")>
<#if keyParam?contains("&")>
<#assign keyParam = keyParam?keep_before("&")>
</#if>
<#assign actionLink = "${properties.appBaseUrl}/verify-email/callback?key=${keyParam}">
</#if>
<#assign requiredActionsText>
<#if requiredActions??>
<#list requiredActions as reqActionItem>
<#if reqActionItem == "VERIFY_EMAIL">Verify your email address</#if>
<#if reqActionItem == "UPDATE_PASSWORD">Update your password</#if>
<#if reqActionItem == "CONFIGURE_TOTP">Set up two-factor authentication</#if>
<#if reqActionItem == "UPDATE_PROFILE">Update your profile</#if>
</#list>
</#if>
</#assign>

Cveil — Action Required

Hi ${user.firstName!"there"},

${requiredActionsText}

Please complete the required action by visiting the following link:

${actionLink}

This link will expire in <#if (linkExpiration &gt; 119)>${(linkExpiration / 60)?floor} hours<#else>${linkExpiration} minutes</#if>.

If you did not request this, you can safely ignore this email.

— The Cveil Team
https://cveil.io
</#outputformat>
