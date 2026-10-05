-- 单用户最多三份有效租约；先检查异常基数，避免腐坏集合导致无界清理。
local limit = tonumber(ARGV[3])
if redis.call('ZCARD', KEYS[1]) > limit then return -2 end
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
local own = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not own and redis.call('ZCARD', KEYS[1]) >= limit then return 0 end
redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[1])
redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
return 1
