-- 过期/已释放UUID不可复活；Redis时钟作为全节点同一时间源。
if redis.call('ZCARD', KEYS[1]) > tonumber(ARGV[3]) then return -2 end
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local own = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not own or tonumber(own) <= now then
    redis.call('ZREM', KEYS[1], ARGV[1])
    if redis.call('ZCARD', KEYS[1]) == 0 then redis.call('DEL', KEYS[1]) end
    return 0
end
redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[1])
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
return 1
