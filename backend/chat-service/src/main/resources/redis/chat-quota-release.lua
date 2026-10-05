-- 不按用户清空；迟到释放只能删除原UUID，不影响新的有效连接。
local removed = redis.call('ZREM', KEYS[1], ARGV[1])
if redis.call('ZCARD', KEYS[1]) == 0 then redis.call('DEL', KEYS[1]) end
return removed
