'use strict';
const patternChoices='<option value="ALL">Every week</option><option value="A">Week A only</option><option value="B">Week B only</option>';
const removeRecord=(entity,id)=>`<button class="btn small danger" data-delete="${entity}" data-id="${esc(id)}">Remove</button>`;
function academic(){
  const s=state.settings;
  return heading('An academic year with context.',`${esc(s.semester_start)} — ${esc(s.semester_end)} · ${esc(s.timezone)}`,admin()?btn('Configure semester','academic-settings'):'')+
    panel('Teaching on a specific date',`<div class="panel-body"><label for="calendar-date">Date</label><input id="calendar-date" type="date" min="${esc(s.semester_start)}" max="${esc(s.semester_end)}" value="${esc(state.today<s.semester_start?s.semester_start:state.today>s.semester_end?s.semester_end:state.today)}">${btn('Show teaching','calendar-show','secondary')}<div id="dated-lessons"></div><p class="muted">Week A begins on the Monday of the first semester week. Holidays and faculty leave cancel affected classes; replacement classes are not automatically created.</p></div>`)+
    panel('Holidays',table(['Date','Holiday',admin()?'Actions':''],state.holidays.map(h=>`<tr><td>${esc(h.holiday_date)}</td><td>${esc(h.label)}</td><td>${admin()?removeRecord('holidays',h.holiday_date):''}</td></tr>`)),admin()?btn('Add holiday','add-holiday','secondary'):'')+
    panel('Approved faculty leave',table(['Faculty','From','Through',admin()?'Actions':''],state.leave.map(l=>`<tr><td>${esc(l.name)}</td><td>${esc(l.start_date)}</td><td>${esc(l.end_date)}</td><td>${admin()?removeRecord('leave',l.id):''}</td></tr>`)),admin()?btn('Record leave','add-leave','secondary'):'');
}
function workflow(){
  return heading('Review before publication.','Generate a draft, submit it, obtain approval from another administrator, then publish.')+
    panel('Drafts and publication history',table(['Version','Status','Author / reviewer','Actions'],state.versions.map(v=>{
      const name=id=>state.users.find(u=>u.id===id)?.name||id||'—';
      let actions=`<button class="btn small secondary" data-version-view="${esc(v.id)}">Preview</button>`;
      for(const [status,op,label] of [['DRAFT','submit','Submit'],['PENDING','approve','Approve'],['PENDING','reject','Reject'],['APPROVED','publish','Publish'],['PUBLISHED','restore','Restore as draft']])if(v.status===status)actions+=`<button class="btn small" data-version-op="${op}" data-id="${esc(v.id)}" ${status==='PENDING'&&v.created_by===me.id?'disabled':''}>${label}</button>`;
      return `<tr><td><strong>${esc(v.title)}</strong><small>${new Date(v.created_at).toLocaleString('en-IN',{timeZone:state.settings.timezone})}</small></td><td>${badge(v.status)}</td><td>${esc(name(v.created_by))}<small>${esc(name(v.reviewed_by))}</small></td><td><div class="actions">${actions}</div></td></tr>`;
    })),btn('Generate draft','generate','secondary'));
}
const importExamples={
  users:'name,email,role,department,password\nNew Faculty,new@campus.edu,FACULTY,Computer Science,ReplaceWithUniquePassword123',
  rooms:'name,capacity\nLecture Hall 201,80',
  courses:'code,title,faculty_email,cohort,students,sessions,week_pattern\nCS501,Distributed Systems,ananya@campus.edu,CSE-5,36,3,A',
  exams:'course_code,room_name,exam_date,slot_no,duration,required_staff\nCS301,Lecture Hall 101,2026-11-07,0,2,2',
  holiday:'holiday_date,label\n2026-12-25,Winter holiday',
  leave:'faculty_email,start_date,end_date\nananya@campus.edu,2026-12-01,2026-12-02'
};
function exchange(){
  return heading('Connect your academic data.','Imports are atomic: a failed row rolls back the entire file. Up to 500 rows per import.',btn('Import CSV','import-csv'))+
    panel('Download data',`<div class="panel-body actions">${[['json','', 'Full academic JSON'],['ics','','Calendar (.ics)'],['csv','lessons','Timetable CSV'],['csv','exams','Exams CSV'],['csv','occurrences','Dated classes CSV'],['csv','users','Directory CSV'],['csv','rooms','Rooms CSV']].map(([format,entity,label])=>`<a class="btn secondary" href="/api/export?format=${format}&entity=${entity}" download>${label}</a>`).join('')}</div>`)+
    panel('Delivery gateways',`<div class="panel-body">${Object.entries(state.gateways).map(([name,configured])=>`<p>${esc(name)} ${badge(configured?'Configured':'Not configured',configured?'':'amber')}</p>`).join('')}<p>Gateway acceptance means the gateway accepted the request; it does not confirm delivery to a person. Configure destinations in Settings. Institutional sync sends an academic JSON snapshot to your configured gateway.</p>${btn('Queue institutional sync','integration-sync','secondary')}</div>`)+
    panel('Recent delivery attempts',table(['Channel','Status','Attempts','Last error','Actions'],state.deliveries.map(d=>`<tr><td>${esc(d.channel)}</td><td>${badge(d.status)}</td><td>${d.attempts}</td><td>${esc(d.last_error||'—')}</td><td>${d.status==='FAILED'?`<button class="btn small" data-delivery-retry="${esc(d.id)}">Retry</button>`:''}</td></tr>`)));
}
function deliveryPreferences(){return panel('Email, SMS and push preferences',`<div class="panel-body"><p>Opt in with an email address, international phone number, or a push token registered with your gateway. Delivery needs a configured gateway.</p>${table(['Channel','Destination','Actions'],state.preferences.map(p=>`<tr><td>${esc(p.channel)}</td><td>${esc(p.destination)}</td><td><button class="btn small danger" data-channel-off="${esc(p.channel)}">Disable</button></td></tr>`))}${btn('Add or update channel','delivery-preference','secondary')}</div>`);}
async function featureAction(name){
  const faculty=state.users.filter(u=>u.role==='FACULTY');
  switch(name){
    case 'academic-settings': {
      const s=state.settings;
      modal('Configure the academic calendar','Changes that conflict with existing assignments are rejected. Pending drafts become stale.',field('Semester start','semester_start','date',s.semester_start)+field('Semester end','semester_end','date',s.semester_end)+field('Campus timezone (IANA)','timezone','text',s.timezone)+field('Teaching days (0=Mon through 6=Sun, comma separated)','teaching_days','text',s.teaching_days.join(','))+`<label for="f-periods">Periods, one HH:mm-HH:mm range per line</label><textarea id="f-periods" name="periods" required>${esc(s.periods.join('\n'))}</textarea>`,b=>api('academic/settings',{...b,teaching_days:b.teaching_days.split(',').map(x=>Number(x.trim())),periods:b.periods.split(/\r?\n/).map(x=>x.trim()).filter(Boolean)}));break;
    }
    case 'add-holiday':modal('Add a holiday','Classes on this date will be cancelled. Existing exams must be moved or removed first.',field('Date','holiday_date','date')+field('Holiday name','label'),b=>api('holiday',b));break;
    case 'add-leave':modal('Record approved leave','Affected classes are cancelled. Existing invigilation conflicts must be resolved first.',select('Faculty','faculty_id',options(faculty))+field('From','start_date','date')+field('Through','end_date','date'),b=>api('leave',b));break;
    case 'calendar-show':{
      const date=$('#calendar-date').value;if(!date)throw Error('Choose a date.');const list=await api('occurrences');const node=$('#dated-lessons');if(node){const dated=list.filter(l=>l.date===date);node.innerHTML=dated.length?lessonRows(dated):empty('No teaching on this date','Check the semester, holidays, leave and course week patterns.');}break;
    }
    case 'delivery-preference':modal('Notification delivery','Saving opts you into future campus notifications.',select('Channel','channel','<option>EMAIL</option><option>SMS</option><option>PUSH</option>')+field('Email, international phone or registered push token','destination'),b=>api('delivery/preferences',{...b,enabled:true}));break;
    case 'import-csv':{
      modal('Import academic records','Creates new records; duplicates are rejected. Download a template, then choose your completed CSV file.',select('Record type','entity',Object.keys(importExamples).map(k=>`<option value="${k}">${k}</option>`).join(''))+`<button class="btn secondary" type="button" id="download-template">Download template</button><label for="import-file">CSV file</label><input id="import-file" type="file" accept=".csv,text/csv" required><p class="muted">Columns may reference faculty_email, course_code and room_name. Period numbers start at 0.</p>`,async b=>{const file=$('#import-file').files[0];if(!file||file.size>900000)throw Error('Choose a CSV file under 900 KB.');await api('imports',{entity:b.entity,csv:await file.text()});},'Validate & import');
      $('#download-template').onclick=()=>{const entity=$('#f-entity').value;const url=URL.createObjectURL(new Blob([importExamples[entity]+'\r\n'],{type:'text/csv'}));const a=document.createElement('a');a.href=url;a.download=entity+'-template.csv';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};break;
    }
    case 'integration-sync':modal('Queue institutional sync','Sends the academic directory, calendar, courses and schedules to the configured institutional gateway.','',()=>api('integration/sync',{}),'Queue sync');break;
    default:return false;
  }
  return true;
}
document.addEventListener('click',async event=>{
  const el=event.target.closest('button');if(!el)return;
  try{
    if(el.dataset.pattern){modal('Change course week pattern','Changes are checked against the current publication.',select('Week pattern','week_pattern',patternChoices.replace('value="'+state.courses.find(c=>c.id===el.dataset.pattern).week_pattern+'"','selected value="'+state.courses.find(c=>c.id===el.dataset.pattern).week_pattern+'"')),b=>api('course/pattern',{...b,id:el.dataset.pattern}));}
    if(el.dataset.versionView){
      const v=state.versions.find(v=>v.id===el.dataset.versionView),context=JSON.parse(v.context_body),lessons=JSON.parse(v.body);
      const rows=lessons.map(l=>`<tr><td>${esc(context.courses.find(c=>c.id===l.course_id)?.code||l.course_id)}</td><td>${esc(context.course_patterns.find(p=>p.course_id===l.course_id)?.week_pattern||'ALL')}</td><td>${days[l.day_no]}</td><td>${esc(context.settings.periods[l.slot_no]||l.slot_no)}</td><td>${esc(context.rooms.find(r=>r.id===l.room_id)?.name||l.room_id)}</td></tr>`);
      const dialog=$('#dialog');$('#dialog-content').innerHTML=`<button class="dialog-close" data-action="close" aria-label="Close">×</button><h2>${esc(v.title)}</h2><p>${esc(v.status)} · ${lessons.length} teaching sessions. ${esc(context.settings.semester_start)} — ${esc(context.settings.semester_end)} · ${esc(context.settings.timezone)}. Values shown are from the draft snapshot.</p>${table(['Course','Week','Day','Period','Room'],rows)}`;dialog.showModal();
    }
    if(el.dataset.versionOp){const op=el.dataset.versionOp;modal(op==='restore'?'Restore as a new draft?':`${op[0].toUpperCase()+op.slice(1)} this schedule?`,op==='publish'?'Replaces the published timetable after checking that the draft is current and conflict-free.':'The existing publication stays unchanged until an approved draft is published.','',()=>api('schedule/'+op,{id:el.dataset.id}),op);}
    if(el.dataset.channelOff){await api('delivery/preferences',{channel:el.dataset.channelOff,enabled:false});await refresh();}
    if(el.dataset.deliveryRetry){await api('delivery/retry',{id:el.dataset.deliveryRetry});await refresh();}
  }catch(error){toast(error.message,true);}
});
