local result = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2])
if result then
    return 1
end
return 0
