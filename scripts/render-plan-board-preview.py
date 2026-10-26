#!/usr/bin/env python3
"""Render compiled plan-board frames with the exact baked vanilla models and font.

Requires the existing location-atelier vanilla_assets.py; never downloads assets.
This is source/asset evidence, not a native Minecraft client capture.
"""
from __future__ import annotations
import argparse,base64,importlib.util,json,math,subprocess,sys
from collections import deque
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from types import SimpleNamespace
import numpy as np
from PIL import Image,ImageDraw,ImageFont
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('placement_preview',Path(__file__).with_name('render-placement-preview.py'))
projection=importlib.util.module_from_spec(spec);spec.loader.exec_module(projection)


def components(points):
    pending=set(points);result=0
    while pending:
        result+=1;queue=deque([pending.pop()])
        while queue:
            x,y=queue.popleft()
            for p in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
                if p in pending:pending.remove(p);queue.append(p)
    return result


def glyph_connectivity(core,stack,out):
    assert stack.advances[ord('█')]==9 and stack.advances[ord(' ')]==4
    assert core.LINE_HEIGHT==9
    cases=[{(0,y) for y in range(5)}|{(2,y) for y in range(5)},
           {(x,0) for x in range(7)}|{(6,y) for y in range(1,5)}|{(x,4) for x in range(2,6)},
           {(x,y) for y in range(4) for x in range(6) if (x+y)%2==0}]
    reports=[]
    for index,cells in enumerate(cases):
        columns=max(x for x,y in cells)+1;rows=max(y for x,y in cells)+1
        bitmap=Image.new('RGBA',(columns*36,rows*9))
        for y in range(rows):
            text=''.join('████' if (x,y) in cells else '         ' for x in range(columns))
            core.draw_text(bitmap,(0,y*9),'#684525'+text,stack,shadow=True)
        alpha=np.asarray(bitmap)[...,3];ink={(int(x),int(y)) for y,x in np.argwhere(alpha>0)}
        occupied={(x//36,y//9) for x,y in ink}
        assert occupied==cells, 'Native glyph ink spilled into a blank cell or lost an occupied cell'
        assert components(ink)==components(cells), 'Composed glyphs changed four-neighbor route connectivity'
        path=out/f'glyph-connectivity-{index}.png';bitmap.resize((bitmap.width*4,bitmap.height*4),Image.Resampling.NEAREST).save(path)
        reports.append(dict(image=path.name,cells=len(cells),cellComponents=components(cells),inkComponents=components(ink),
                            glyphAdvance=9,spaceAdvance=4,lineHeight=9,shadowOffset=1))
    return reports


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('scene','client','font-core','baker','out'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--only');parser.add_argument('--views',default='player,overview,front,side,top')
    args=parser.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    sys.path.insert(0,str(args.font_core.parent));import minecraft_surface_core as core
    glyphs,advances=core.load_font_glyphs(args.client,faithful32=None,raster_scale=1)
    stack=SimpleNamespace(glyphs=glyphs,advances=advances,raster_scale=1)
    proof=glyph_connectivity(core,stack,args.out)
    data=json.loads(args.scene.read_text())
    materials={p['material'] for s in data['states'] for p in s['pieces']}|{'minecraft:grass_block[snowy=false]'}
    recipe={'palette':{str(i):{'block':m} for i,m in enumerate(sorted(materials))}}
    recipe_path=args.out/'palette-input.json';recipe_path.write_text(json.dumps(recipe))
    palette=json.loads(subprocess.check_output(['python3',str(args.baker),str(args.client),str(recipe_path)],timeout=60))
    (args.out/'palette.json').write_text(json.dumps(palette))
    textures={k:np.asarray(Image.open(BytesIO(base64.b64decode(v.split(',')[1]))).convert('RGBA')) for k,v in palette['textures'].items()}

    def rotate_y(vertices,degrees):
        angle=math.radians(degrees);c,s=math.cos(angle),math.sin(angle)
        return np.asarray(vertices)@np.array([[c,0,-s],[0,1,0],[s,0,c]])
    def model(view,piece):
        block=palette['blocks'][piece['material']];size=np.array([piece[k] for k in ('width','height','depth')])
        center=np.array([piece[k] for k in ('x','y','z')]);angle=piece.get('rotationY',0)
        for q in block['quads']:
            texture=textures[q['texture']]
            if q.get('tint')=='grass':texture=texture.copy();texture[...,:3]=(texture[...,:3]*np.array([.55,.73,.32])).astype(np.uint8)
            vertices=rotate_y((np.asarray(q['vertices'])-.5)*size,angle)+center
            normal=rotate_y([q['normal']],angle)[0];shade=.80+.16*max(0,normal[1])+.04*max(0,normal[2])
            view.quad(vertices,texture,shade=shade,uv=q['uv'])
    def label(view,label):
        lines=label['text'].split('\n');width=max(1,max(core.measured_width(line,advances) for line in lines));height=len(lines)*9
        bitmap=Image.new('RGBA',(width,height))
        for row,line in enumerate(lines):
            line_width=core.measured_width(line,advances)
            x=0 if label['alignment']=='LEFT' else (width-line_width)//2
            core.draw_text(bitmap,(x,row*9),label['color']+line,stack,shadow=label['shadow'])
        w=width*.025*label['sx'];h=height*.025*label['sy']
        at=np.array([label[k] for k in ('x','y','z')])
        local=np.array([[-w/2,h,0],[w/2,h,0],[w/2,0,0],[-w/2,0,0]])
        view.quad(rotate_y(local,label.get('rotationY',0))+at,np.asarray(bitmap))
    reports=[]
    for state in data['states']:
        if args.only and state['id']!=args.only:continue
        cameras={'player':(state['eye'],state['target'],(0,1,0),65),
                 'overview':((11,8,13),(3.5,2.5,1.5),(0,1,0),48),
                 'front':((3.5,3,15),(3.5,3,1.5),(0,1,0),30),
                 'side':((17,3.0,1.5),(3.5,2.8,1.5),(0,1,0),32),
                 'top':((3.5,17,1.5),(3.5,0,1.5),(0,0,-1),30)}
        cameras.update({name:(value['eye'],value['target'],value['up'],value['fov'])
                        for name,value in state.get('cameras',{}).items()})
        for name in args.views.split(','):
            eye,target,up,fov=cameras[name];view=projection.View(eye,target,width=1600,height=1000,fov=fov,up=up)
            for x in range(-8,15):
                for z in range(-8,16):
                    model(view,dict(x=x+.5,y=.5,z=z+.5,width=1,height=1,depth=1,material='minecraft:grass_block[snowy=false]'))
            for piece in state['pieces']:model(view,piece)
            for text in state['labels']:label(view,text)
            result=Image.new('RGB',(1600,1060),(24,29,29));result.paste(view.image(),(0,60))
            draw=ImageDraw.Draw(result);font=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',22)
            draw.text((20,15),f"{state['id']} · {name} · {state['displayParts']} packet parts · compiled source, no native client",font=font,fill=(235,233,214))
            filename=f"{state['id']}-{name}.png";result.save(args.out/filename)
            reports.append(dict(image=filename,state=state['id'],view=name,eye=eye,target=target,displayParts=state['displayParts']))
            print(filename,flush=True)
    report={'evidence':data['evidence'],'sourceSha256':data['sourceSha256'],'sceneSha256':sha256(args.scene.read_bytes()).hexdigest(),
            'rendererSha256':sha256(Path(__file__).read_bytes()).hexdigest(),'clientSha256':sha256(args.client.read_bytes()).hexdigest(),
            'glyphConnectivity':proof,'views':reports,'limitations':['Fixture ground; native lighting and client packet acceptance unobserved',
            'TextDisplay glyph baseline follows the source font metrics; native perspective and raster filtering remain unobserved']}
    (args.out/'render-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    html='<!doctype html><meta charset="utf-8"><title>ArcCivs · Settlement plan board</title><style>body{background:#181d1d;color:#eee8d4;font:16px system-ui;margin:24px}img{width:100%;max-width:1600px}figure{margin:24px 0}a{color:#e2bd72}</style><h1>ArcCivs · План поселения</h1><p>Compiled geometry and vanilla assets. Native client not observed.</p>'
    html+=''.join(f'<figure><figcaption>{r["state"]} · {r["view"]}</figcaption><a href="{r["image"]}"><img src="{r["image"]}"></a></figure>' for r in reports)
    (args.out/'index.html').write_text(html)

if __name__=='__main__':main()
