import sys
from PIL import Image, ImageChops, ImageFilter
t = Image.open(sys.argv[1]).convert('RGB')
r = Image.open(sys.argv[2]).convert('RGB')
d = ImageChops.difference(t, r).convert('L')
# ignore status bar
d.paste(0, (0, 0, d.width, 130))
px = d.load()
W, H = d.size
big = sum(1 for y in range(0, H, 2) for x in range(0, W, 2) if px[x, y] > 40)
print('pixels>40 (sampled/4):', big, ' max:', d.getextrema()[1])
# amplified diff over darkened tiled frame
amp = d.point(lambda v: 255 if v > 25 else 0).filter(ImageFilter.MaxFilter(5))
base = t.point(lambda v: v // 3)
red = Image.new('RGB', t.size, (255, 40, 40))
out = Image.composite(red, base, amp)
out.resize((540, 1200)).save(sys.argv[3])
