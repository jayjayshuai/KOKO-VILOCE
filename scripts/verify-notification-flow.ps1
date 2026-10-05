param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [switch]$ConfirmTestData
)

$ErrorActionPreference = 'Stop'
if (-not $ConfirmTestData) {
    throw 'This script creates test accounts and content. Pass -ConfirmTestData and clean the returned IDs afterward.'
}
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$testPassword = [guid]::NewGuid().ToString('N') + 'Aa!9'
$owner = $null
$actor = $null
$post = $null
$comment = $null

function Invoke-Api($Method, $Path, $Token, $Payload) {
    $headers = @{}
    if ($Token) { $headers[$Token.tokenName] = $Token.tokenValue }
    $parameters = @{ Uri = "$BaseUrl$Path"; Method = $Method; Headers = $headers; TimeoutSec = 20 }
    if ($null -ne $Payload) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Payload | ConvertTo-Json -Depth 8 -Compress
    }
    Invoke-RestMethod @parameters
}

function Wait-ForNotification($Token, $Type) {
    for ($attempt = 0; $attempt -lt 16; $attempt++) {
        $page = Invoke-Api GET '/notifications?page=1&size=20' $Token $null
        $match = @($page.items | Where-Object { $_.eventType -eq $Type })
        if ($match.Count -gt 0) { return $match[0] }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for $Type notification"
}

try {
    $owner = Invoke-Api POST '/auth/register' $null @{
        email = "qa-owner-$suffix@example.invalid"; password = $testPassword
        handle = "qaowner$suffix"; displayName = 'Notification QA Owner'
    }
    $actor = Invoke-Api POST '/auth/register' $null @{
        email = "qa-actor-$suffix@example.invalid"; password = $testPassword
        handle = "qaactor$suffix"; displayName = 'Notification QA Actor'
    }
    $profile = Invoke-Api PUT '/creators/me' $owner @{
        slug = "qa-creator-$suffix"; displayName = 'Notification QA Owner'
        headline = 'Notification verification'; bio = 'Temporary verification account'
        avatarUrl = ''; bannerUrl = ''; version = 0
    }
    $profile = Invoke-Api POST "/creators/me/publish?version=$($profile.version)" $owner $null
    $follow = Invoke-Api PUT "/creators/$($owner.user.id)/follow" $actor $null
    if (-not $follow.followedByMe) { throw 'Follow did not persist' }
    $followNotice = Wait-ForNotification $owner 'FOLLOW'
    $post = Invoke-Api POST '/content/posts' $owner @{
        slug = "qa-post-$suffix"; title = 'Notification QA Post'
        excerpt = 'Temporary verification'; body = 'Temporary verification content'
        coverUrl = ''
    }
    $post = Invoke-Api POST "/content/posts/$($post.id)/publish?version=$($post.version)" $owner $null
    $comment = Invoke-Api POST "/content/posts/$($post.id)/comments" $actor @{ body = 'Notification QA comment' }
    $commentNotice = Wait-ForNotification $owner 'COMMENT'
    Invoke-Api PUT "/notifications/$($followNotice.id)/read" $owner $null | Out-Null
    $readBack = Invoke-Api GET '/notifications?page=1&size=20' $owner $null
    $readNotice = $readBack.items | Where-Object { $_.id -eq $followNotice.id } | Select-Object -First 1
    if (-not $readNotice.readAt) { throw 'Mark read did not persist' }
    $saved = Invoke-Api PUT '/notifications/preferences/COMMENT' $owner @{ enabled = $false }
    if ($saved.enabled) { throw 'Preference did not persist' }
    [pscustomobject]@{
        result = 'PASS'; cleanupRequired = $true; suffix = $suffix
        ownerId = $owner.user.id; actorId = $actor.user.id
        postId = $post.id; commentId = $comment.id
        followNotificationId = $followNotice.id; commentNotificationId = $commentNotice.id
    } | ConvertTo-Json -Compress
} catch {
    [pscustomobject]@{
        result = 'FAIL'; cleanupRequired = $true; suffix = $suffix
        ownerId = $owner.user.id; actorId = $actor.user.id
        postId = $post.id; commentId = $comment.id; error = $_.Exception.Message
    } | ConvertTo-Json -Compress
    exit 1
}
