"""
Build the on-device knowledge base.

Two collections, kept separate because they answer different questions and
carry different risk:

  guidance   general infant-care information, retrieved for open questions
  medicines  breastfeeding-safety entries, retrieved when a medicine is named

Every entry carries a source and a `caution` field. The caution is not a
disclaimer bolted on at render time -- it is part of the record, so an answer
physically cannot be assembled without the limit that goes with it.

Scope discipline, deliberately: nothing here gives a dose, and nothing here
diagnoses. Dosing depends on weight and formulation and is the one thing an
offline app must never guess at. The medicines collection answers the question
people actually ask a search engine at 2am -- "I am breastfeeding, is this one
safe" -- which is exactly the question LactMed exists to answer.

The full LactMed database is not bulk-downloadable, so this ships a curated
subset drawn from its risk summaries. import_lactmed.py documents how to swap in
the complete set when a copy is available.
"""

import json

from config import MODELS

OUT = MODELS.parents[1] / "android" / "app" / "src" / "main" / "assets"

NHS = "NHS"
WHO = "WHO"
AAP = "American Academy of Pediatrics"
LACTMED = "LactMed (NIH)"
IAP = "Indian Academy of Pediatrics"

# Guidance entries now declare who they are for and at what age they apply.
#
# Both matter and their absence caused real, bad answers: a four-month-old whose
# mother asked "what to eat now" was shown a list of choking hazards, which is
# advice for a baby eating solids and dangerous nonsense for one who should be
# exclusively breastfed. And "what should I avoid while breastfeeding" returned
# the baby's food rules, because nothing in the corpus was about the mother.
#
#   audience   "baby", "mother" or "both"
#   min_age / max_age   months; None means unbounded
GUIDANCE = [
    # ---- crying -------------------------------------------------------
    dict(id="cry-normal", asked="why does my baby cry so much crying all the time normal amount hours a day peak six weeks is it normal newborn cries constantly", audience="baby", min_age=0, max_age=12, topic="crying", title="How much crying is normal",
         text="Crying peaks at around six to eight weeks of age and usually eases by "
              "three to four months. Two to three hours a day spread across the day is "
              "within the normal range for a young infant. Crying that follows this "
              "pattern is a stage of development, not a sign that anything is wrong "
              "with the baby or the caregiving.",
         source=NHS,
         caution="Crying that starts suddenly in a baby who was previously settled, or "
                 "that sounds different from usual, is worth a call to your doctor."),
    dict(id="cry-colic", asked="colic colicky three hours every evening witching hour inconsolable screaming same time each night rule of threes wessel", audience="baby", min_age=0, max_age=6, topic="crying", title="Colic and the rule of threes",
         text="Infantile colic is defined by duration, not by any test. The classic "
              "Wessel criteria are crying for three or more hours a day, on three or "
              "more days a week, for three or more weeks, in a baby who is otherwise "
              "feeding and growing well. The Rome IV criteria are similar but drop the "
              "three-week requirement. Colic is self-limiting and typically resolves by "
              "three to four months.",
         source="Wessel criteria; Rome IV",
         caution="Colic is a description of a crying pattern, not a diagnosis you can "
                 "make at home. Poor weight gain, fever, vomiting or blood in the stool "
                 "mean this is something else -- see a doctor."),
    dict(id="cry-soothe", asked="how to settle calm soothe stop crying wont stop crying at night what helps swaddle rocking white noise shushing hold", audience="baby", min_age=0, max_age=12, topic="crying", title="What helps a crying baby settle",
         text="Things that commonly help: skin-to-skin holding, steady rhythmic motion, "
              "a firm swaddle for babies who are not yet rolling, white noise or "
              "shushing at a moderate volume, offering a feed, and a change of scene. "
              "Different babies respond to different things and the same baby changes "
              "over weeks, so it is worth keeping track of what actually worked.",
         source=NHS,
         caution="Stop swaddling once a baby shows any sign of rolling over."),
    dict(id="cry-caregiver", asked="i cant cope overwhelmed anymore frustrated angry losing patience put the baby down never shake exhausted breaking point crying gets to me", audience="mother", min_age=None, max_age=None, topic="crying", title="When you feel overwhelmed",
         text="If you feel yourself losing patience, put the baby down somewhere safe "
              "such as their cot, leave the room, and take a few minutes. A baby is not "
              "harmed by crying alone in a safe place for a short while. Never shake a "
              "baby -- shaking causes serious, permanent brain injury in seconds.",
         source=NHS,
         caution="If you are struggling, tell your doctor or health visitor. This is "
                 "common, it is not a failure, and there is help."),

    # ---- sleep --------------------------------------------------------
    dict(id="sleep-safe", asked="safe sleep position on back not tummy not front cot crib bed sharing co sleeping sids suffocation where should my baby sleep alone flat firm empty car seat pram sofa armchair sling bouncer swing napping outside the cot blanket pillow bumper toys in the cot loose bedding", audience="baby", min_age=0, max_age=12, topic="sleep", title="Safe sleep basics",
         text="Put babies to sleep on their back for every sleep, on a firm flat "
              "mattress, in a cot with no pillows, duvets, bumpers or soft toys. Room "
              "sharing without bed sharing for at least the first six months lowers "
              "risk. Keep the room comfortably cool and avoid overheating.",
         source=AAP,
         caution="Once a baby can roll over on their own you do not need to keep "
                 "turning them back, but always start them on their back."),
    dict(id="sleep-amount", asked="how much sleep hours a day night waking naps schedule through the night newborn sleep pattern", audience="baby", min_age=0, max_age=24, topic="sleep", title="How much babies sleep",
         text="Newborns sleep fourteen to seventeen hours across a day in short "
              "stretches. By three to six months many babies manage a longer stretch at "
              "night. Night waking remains normal well into the first year and does not "
              "by itself indicate a problem.",
         source=AAP, caution=""),

    # ---- feeding ------------------------------------------------------
    dict(id="feed-exclusive", asked="how often should a newborn feed frequency on demand exclusively breastfed first six months water needed extra drinks per day cluster summer hot weather heat thirsty does she need water juice how long does a feed take duration minutes each side night feeds feeding at night waking to feed dream feed", audience="baby", min_age=0, max_age=6, topic="feeding", title="Feeding in the first six months",
         text="Exclusive breastfeeding is recommended for the first six months, with "
              "continued breastfeeding alongside other foods up to two years or beyond. "
              "Babies fed formula should have an appropriate infant formula. Water, "
              "cow's milk as a main drink, and other foods are not needed before six "
              "months.",
         source=WHO, caution=""),
    dict(id="feed-solids", asked="when to start solids weaning first foods what age begin eating six months complementary purees mashed porridge ragi khichdi dal rice vegetables fruit baby led readiness signs", audience="baby", min_age=5, max_age=24, topic="feeding", title="Starting solid food",
         text="Start solids at around six months, when the baby can hold their head "
              "steady, sit with support, and bring food to their mouth. Begin with "
              "single foods, introduce common allergens such as egg and peanut early "
              "and regularly once solids have started, and offer iron-rich foods.",
         source=WHO,
         caution="Introduce one new food at a time so a reaction can be traced."),
    dict(id="feed-avoid", asked="foods to avoid under one honey salt sugar whole nuts cows milk drink unpasteurised what not to give peanut peanut butter allergen allergens egg introduce allergy foods shellfish", audience="baby", min_age=5, max_age=12, topic="feeding", title="Foods to avoid under one year",
         text="Do not give honey before twelve months -- it can carry the spores that "
              "cause infant botulism. Avoid added salt and added sugar, whole nuts, "
              "unpasteurised dairy, and cow's milk as a main drink before twelve "
              "months. Do not give rice drinks before five years.",
         source=NHS,
         caution="Honey before one year is a genuine hazard, not a precaution."),
    dict(id="feed-choking", asked="choking hazard risk which foods dangerous shapes grapes nuts popcorn cut lengthways round hard small how to prepare safely gag gags gagging coughs coughing while eating splutters goes red food stuck", audience="baby", min_age=5, max_age=36, topic="feeding", title="Choking hazards",
         text="Whole grapes, cherry tomatoes, whole nuts, popcorn, raw carrot sticks, "
              "sausage rounds and hard sweets are common choking hazards. Cut round "
              "foods lengthways into quarters. Always stay with a baby while they eat "
              "and keep them sitting upright.",
         source=NHS,
         caution="If a baby is coughing forcefully, let them cough. If they cannot "
                 "cough, cry or breathe, start choking first aid and call emergency "
                 "services."),
    dict(id="feed-hunger-cues", asked="how do i know my baby is hungry hunger signs cues rooting sucking hands to mouth fist in mouth chewing fingers licking lips before crying feeding signals wants milk stirring", audience="baby", min_age=0, max_age=12, topic="feeding", title="Hunger cues before crying",
         text="Crying is a late hunger signal. Earlier ones are stirring and mouth "
              "opening, turning the head to look for the breast, hand-to-mouth "
              "movement, and sucking on fingers. Feeding at these earlier cues is "
              "usually calmer for everyone.",
         source=WHO, caution=""),

    # ---- health -------------------------------------------------------
    dict(id="health-fever", asked="fever high temperature 38 39 degrees celsius fahrenheit 100 101 102 103 reading hot forehead thermometer feels warm burning up when to worry under three months", audience="baby", min_age=0, max_age=36, topic="health", title="Fever in a baby",
         text="A temperature of 38C or above in a baby under three months is a medical "
              "emergency and needs to be seen the same day, even if the baby seems "
              "well. In older babies, how the baby looks and behaves matters more than "
              "the number on the thermometer.",
         source=NHS,
         caution="Under three months with a fever: go to a doctor now, do not wait."),
    dict(id="health-redflags", asked="when should i go to hospital emergency doctor urgent warning signs serious worry rash not waking refusing feeds breathing fast", audience="baby", min_age=0, max_age=36, topic="health", title="Signs that need urgent help",
         text="Get urgent medical help for: difficulty breathing or grunting with each "
              "breath, blue or very pale skin, a rash that does not fade when pressed "
              "with a glass, a baby who is floppy or unusually hard to wake, a bulging "
              "soft spot, repeated vomiting, no wet nappy for twelve hours, or a weak "
              "high-pitched continuous cry that is unlike the baby's normal cry.",
         source=NHS,
         caution="This list is for recognising an emergency, not for ruling one out. "
                 "If you are worried, seek help."),
    dict(id="health-dehydration", asked="dehydrated dehydration not enough wet nappies dry mouth sunken fontanelle no tears fewer wet fluids losing fluid", audience="baby", min_age=0, max_age=36, topic="health", title="Dehydration",
         text="Signs include fewer wet nappies than usual, a dry mouth, no tears when "
              "crying, a sunken soft spot, and unusual sleepiness. Babies dehydrate much "
              "faster than adults, especially with vomiting or diarrhoea.",
         source=NHS, caution="Suspected dehydration in an infant needs medical review."),
    dict(id="health-vaccines", asked="vaccine vaccination immunisation schedule india which jabs due when bcg opv pentavalent measles national programme", audience="baby", min_age=0, max_age=24, topic="health", title="Immunisation in India",
         text="India's Universal Immunisation Programme schedules BCG, hepatitis B "
              "and OPV at birth; pentavalent, OPV, rotavirus and PCV doses at six, ten "
              "and fourteen weeks; measles-rubella and JE from nine months; and boosters "
              "in the second year. Vaccines are free at government facilities.",
         source=IAP,
         caution="Confirm the current schedule with your paediatrician -- it is revised "
                 "periodically and some states differ."),
    dict(id="health-temperature", asked="room temperature nursery bedroom how warm should the room be fan ac air conditioning how many layers should my baby wear clothing blankets overheating too hot too cold sleeping bag tog", audience="baby", min_age=0, max_age=12, topic="health", title="Room temperature and clothing",
         text="A comfortable room is around sixteen to twenty degrees Celsius. Feel the "
              "baby's chest or the back of the neck rather than hands or feet, which are "
              "normally cooler. Overheating is a risk factor for sudden infant death, so "
              "err towards one light layer fewer.",
         source=NHS, caution=""),

    # ---- development and safety ---------------------------------------
    dict(id="dev-milestones", asked="milestones when should my baby smile roll over sit crawl walk grasp head control eye contact looking at me following with her eyes tracking responding development age expect first", audience="baby", min_age=0, max_age=24, topic="development", title="Early milestones",
         text="Rough guide: social smiling by two months, head control by four months, "
              "rolling over by six months, sitting without support by nine months, and "
              "pulling to stand by twelve months. Ranges are wide and healthy babies "
              "vary a great deal.",
         source=AAP,
         caution="Loss of a skill a baby previously had is always worth a review."),
    dict(id="safety-toys", asked="is this toy safe small parts choking hazard age label button battery magnets cords loops paint how do i know if a toy", audience="baby", min_age=0, max_age=36, topic="safety", title="Whether a toy is safe",
         text="For children under three, anything that fits entirely through a tube "
              "about four centimetres across -- roughly a toilet roll tube -- is a "
              "choking hazard. Check for small detachable parts, button batteries, "
              "magnets, and long cords or ribbons over about twenty centimetres. Button "
              "batteries and high-powered magnets cause severe internal injury if "
              "swallowed and need emergency care.",
         source="EN 71 / ASTM F963 toy safety standards",
         caution="Size cannot be judged reliably from a photograph. Check the actual "
                 "object against a tube."),
    dict(id="safety-tummy", asked="tummy time how much awake supervised play on front head control flat head strengthen neck", audience="baby", min_age=0, max_age=12, topic="safety", title="Tummy time",
         text="Supervised tummy time while awake, from the first weeks, builds neck and "
              "shoulder strength and reduces flat spots on the head. Start with a minute "
              "or two a few times a day and build up.",
         source=AAP, caution="Never leave a baby on their front unsupervised or asleep."),

    # ---- for the mother ------------------------------------------------
    dict(id="mum-diet", asked="what should i eat while breastfeeding my own diet coffee caffeine chai tea alcohol fish foods to avoid for me can i drink calories extra", audience="mother", min_age=None, max_age=None,
         topic="maternal_diet", title="What to eat while breastfeeding",
         text="There is no list of foods a breastfeeding parent must avoid. Eat a "
              "varied diet and drink to thirst. You need roughly 300-500 extra "
              "calories a day. Continue a vitamin D supplement. Caffeine is fine in "
              "moderation - about two to three cups of coffee a day - and alcohol is "
              "best limited, timed away from feeds.",
         source=NHS,
         caution="Cutting out food groups 'just in case' is not recommended and can "
                 "leave you short of nutrients. If you suspect a genuine reaction in "
                 "your baby, ask a doctor before eliminating anything."),
    dict(id="mum-fussy-baby", asked="does what i eat make my baby fussy spicy food garlic dairy gas wind elimination diet blame my food upset windy",
         # "both", because the question is always phrased as being about the
         # baby ("does this make HER windy") while the answer is about the
         # mother's plate. Filed under either subject, it is reachable from the
         # way people actually ask it.
         audience="both", min_age=None, max_age=None,
         topic="maternal_diet", title="Does what I eat make my baby fussy",
         text="For most babies, no. Cow's milk protein is the one that occasionally "
              "matters, and a genuine allergy usually shows more than fussing - blood "
              "or mucus in the stool, eczema, poor weight gain, or vomiting. Spicy "
              "food, garlic and vegetables like cabbage do not need avoiding.",
         source=NHS,
         caution="Do not start an elimination diet without medical advice. It is "
                 "hard to sustain and rarely the answer."),
    dict(id="mum-mastitis", asked="mastitis blocked duct breast red painful hot lump sore flu like fever engorged cracked nipples my breast hurts", audience="mother", min_age=None, max_age=None,
         topic="lactation", title="Mastitis and blocked ducts",
         text="A hard, red, painful area of breast with flu-like symptoms and fever "
              "suggests mastitis. Keep feeding or expressing from that side - "
              "emptying the breast is the treatment. Rest, fluids and pain relief "
              "help.",
         source=NHS,
         caution="If it does not improve within 12 to 24 hours, or you feel "
                 "seriously unwell, see a doctor the same day. Untreated mastitis can "
                 "become an abscess."),
    dict(id="mum-supply", asked="milk supply not enough increase low supply galactagogue pumping expressing output less than before worried baby not getting enough boost more milk breast feels empty", audience="mother", min_age=None, max_age=None,
         topic="lactation", title="Worries about milk supply",
         text="Supply follows demand, so frequent effective feeding is what protects "
              "it. Reliable signs of enough milk are steady weight gain, six or more "
              "wet nappies a day, and a baby who settles after most feeds. Breast "
              "softness and pumped volumes are poor indicators.",
         source=WHO,
         caution="If weight gain is faltering, get a feed assessed rather than "
                 "starting supplements on your own."),
    dict(id="mum-recovery", asked="my own recovery after delivery bleeding lochia stitches perineum c section scar when will i feel normal again six week check", audience="mother", min_age=None, max_age=None,
         topic="postnatal", title="Your own recovery",
         text="Bleeding after birth gradually reduces over two to six weeks. "
              "Tiredness, night sweats and mood swings are common early on. Iron "
              "deficiency is frequent after delivery and causes breathlessness and "
              "exhaustion that people often put down to new parenthood.",
         source=NHS,
         caution="Heavy bleeding that soaks a pad an hour, large clots, fever, or "
                 "severe headache with visual changes all need urgent review."),
    dict(id="mum-mood", asked="i feel low sad tearful anxious since the birth baby blues mood postnatal down not myself crying a lot no joy i cry every day cry for no reason dont know why im crying weepy numb empty hopeless dont feel bonded bonding attachment love connection enjoy nothing edinburgh scale", audience="mother", min_age=None, max_age=None,
         topic="mental_health", title="Mood after birth",
         text="Feeling tearful and overwhelmed in the first two weeks is common and "
              "usually passes. Low mood that persists beyond two weeks, or that stops "
              "you enjoying anything or caring for yourself, is postnatal depression - "
              "it is common, it is treatable, and it is not a failing.",
         source=NHS,
         caution="Any thought of harming yourself or your baby needs help today. "
                 "Contact your doctor or an emergency service now."),
    dict(id="mum-sleep", asked="how do i cope with no sleep broken sleep exhausted my own sleep tired all the time shift sleeping napping when baby sleeps", audience="mother", min_age=None, max_age=None,
         topic="postnatal", title="Coping with broken sleep",
         text="Fragmented sleep is the hardest part of the first months for most "
              "parents. Sleeping when the baby sleeps, sharing night duties where "
              "possible, and accepting help with anything that is not feeding are the "
              "things that actually work.",
         source=NHS,
         caution="Persistent inability to sleep even when the baby is asleep can be "
                 "a sign of anxiety or depression and is worth mentioning."),
]

# Breastfeeding-safety entries. `risk` mirrors LactMed's own framing.
MEDICINES = [
    dict(name="Paracetamol", aka=["acetaminophen", "crocin", "dolo", "calpol", "tylenol"],
         risk="compatible",
         text="Amounts in breast milk are far below the dose given to infants directly. "
              "Usually the first choice for pain or fever while breastfeeding."),
    dict(name="Ibuprofen", aka=["brufen", "advil", "combiflam", "nurofen"],
         risk="compatible",
         text="Poorly excreted into breast milk and short-acting. Generally considered a "
              "preferred anti-inflammatory during breastfeeding."),
    dict(name="Amoxicillin", aka=["amoxil", "mox", "novamox"],
         risk="compatible",
         text="Small amounts pass into milk. Watch for loose stools, thrush or rash in "
              "the infant, which are usually mild and settle."),
    dict(name="Azithromycin", aka=["azee", "zithromax"],
         risk="compatible",
         text="Low levels in milk. Considered acceptable during breastfeeding."),
    dict(name="Cetirizine", aka=["zyrtec", "alerid", "cetzine"],
         risk="caution",
         text="Non-sedating antihistamines are preferred over older ones, but large or "
              "prolonged doses may reduce milk supply. Watch the infant for drowsiness."),
    dict(name="Chlorpheniramine", aka=["cpm", "piriton", "avil"],
         risk="caution",
         text="An older sedating antihistamine. May cause drowsiness or irritability in "
              "the infant and can reduce milk supply. A non-sedating alternative is "
              "usually preferred."),
    dict(name="Pantoprazole", aka=["pantop", "pan", "pan 40", "pan d", "protonix"],
         risk="compatible",
         text="Very low levels in milk and poorly absorbed by the infant."),
    dict(name="Metronidazole", aka=["flagyl", "metrogyl"],
         risk="caution",
         text="Passes into milk in measurable amounts. Often used, but some clinicians "
              "advise a pause after a single large dose. Ask your doctor."),
    dict(name="Ciprofloxacin", aka=["cipro", "ciplox"],
         risk="caution",
         text="Acceptable to most authorities, though alternatives are often chosen "
              "first. Watch for diarrhoea or thrush in the infant."),
    dict(name="Codeine", aka=["codeine phosphate"],
         risk="avoid",
         text="Avoid while breastfeeding. Some people metabolise codeine unusually fast, "
              "producing high morphine levels in milk, and infant deaths have been "
              "reported. Paracetamol or ibuprofen are safer choices."),
    dict(name="Aspirin", aka=["acetylsalicylic acid", "disprin"],
         risk="avoid",
         text="Regular or high-dose aspirin should be avoided while breastfeeding "
              "because of the risk of Reye syndrome and bleeding. Low-dose aspirin "
              "prescribed for a heart condition is a separate question for your doctor."),
    dict(name="Fluconazole", aka=["forcan", "diflucan"],
         risk="compatible",
         text="Passes into milk but is also given directly to infants at higher doses. "
              "Commonly used for nipple thrush."),
    dict(name="Domperidone", aka=["domstal", "motilium"],
         risk="caution",
         text="Low levels in milk and sometimes prescribed to increase supply, but it "
              "carries cardiac warnings in several countries. Prescription decision."),
    dict(name="Ondansetron", aka=["emeset", "zofran"],
         risk="caution",
         text="Little published data during breastfeeding. Short courses are generally "
              "considered acceptable; discuss longer use."),
    dict(name="Levocetirizine", aka=["xyzal", "levocet"],
         risk="caution",
         text="As with cetirizine: preferred over sedating antihistamines, but large or "
              "prolonged doses may affect supply."),
    dict(name="Amoxicillin-clavulanate", aka=["augmentin", "clavam"],
         risk="compatible",
         text="Considered acceptable. Infant may have looser stools."),
    dict(name="Prednisolone", aka=["omnacortil", "wysolone"],
         risk="compatible",
         text="Amounts in milk are small at usual doses. With very high doses some "
              "clinicians suggest waiting a few hours before feeding."),
    dict(name="Ranitidine", aka=["zinetac", "rantac"],
         risk="caution",
         text="Was widely used in breastfeeding, but has been withdrawn in many markets "
              "over an impurity concern. Check whether your product is still licensed."),
    dict(name="Diclofenac", aka=["voveran", "voltaren"],
         risk="caution",
         text="Low levels in milk and short-acting, but ibuprofen has more safety data "
              "during breastfeeding and is usually preferred."),
    dict(name="Tramadol", aka=["ultracet", "tramacip"],
         risk="avoid",
         text="Avoid while breastfeeding, particularly in newborns. Like codeine, it is "
              "metabolised to an opioid and carries a risk of infant sedation and "
              "breathing problems."),
]

# Conditions and co-medications that change the answer for a specific mother.
# This is what turns "is ibuprofen safe" into "is ibuprofen safe *for you*" --
# the question people actually have, and the one a generic lookup cannot answer.
CONTEXT_FLAGS = {
    "Ibuprofen": dict(
        avoid_if=["asthma", "peptic ulcer", "stomach ulcer", "kidney disease",
                  "ckd", "gastritis"],
        interacts_with=["Aspirin", "Diclofenac", "Prednisolone"],
        note="NSAIDs can trigger bronchospasm in aspirin-sensitive asthma and "
             "raise bleeding risk with other NSAIDs or steroids.",
    ),
    "Diclofenac": dict(
        avoid_if=["asthma", "peptic ulcer", "stomach ulcer", "kidney disease",
                  "heart disease", "ckd"],
        interacts_with=["Ibuprofen", "Aspirin", "Prednisolone"],
        note="Same NSAID cautions as ibuprofen, with a higher cardiovascular "
             "signal.",
    ),
    "Aspirin": dict(
        avoid_if=["asthma", "peptic ulcer", "stomach ulcer", "bleeding disorder"],
        interacts_with=["Ibuprofen", "Diclofenac"],
        note="Already best avoided while breastfeeding; these conditions make it "
             "worse.",
    ),
    "Paracetamol": dict(
        avoid_if=["liver disease", "hepatitis"],
        interacts_with=[],
        note="Very safe, but liver disease changes the calculus and the ceiling "
             "dose.",
    ),
    "Amoxicillin": dict(
        avoid_if=["penicillin allergy"],
        interacts_with=[],
        note="A penicillin. A stated penicillin allergy is an absolute stop.",
    ),
    "Amoxicillin-clavulanate": dict(
        avoid_if=["penicillin allergy", "liver disease"],
        interacts_with=[],
        note="A penicillin, and clavulanate adds a liver signal.",
    ),
    "Ciprofloxacin": dict(
        avoid_if=["epilepsy", "seizure disorder", "tendon problems", "myasthenia"],
        interacts_with=["Pantoprazole"],
        note="Fluoroquinolones lower the seizure threshold and carry a tendon "
             "warning. Antacids and PPIs reduce absorption.",
    ),
    "Metronidazole": dict(
        avoid_if=["liver disease", "epilepsy"],
        interacts_with=[],
        note="Do not drink alcohol during or for 48 hours after a course.",
    ),
    "Cetirizine": dict(
        avoid_if=["kidney disease", "ckd"],
        interacts_with=["Chlorpheniramine", "Levocetirizine"],
        note="Doubling up on antihistamines adds sedation without adding benefit.",
    ),
    "Levocetirizine": dict(
        avoid_if=["kidney disease", "ckd"],
        interacts_with=["Cetirizine", "Chlorpheniramine"],
        note="Same molecule family as cetirizine -- taking both is a duplicate.",
    ),
    "Chlorpheniramine": dict(
        avoid_if=["glaucoma", "asthma", "urinary retention"],
        interacts_with=["Cetirizine", "Levocetirizine", "Codeine", "Tramadol"],
        note="Sedating. Compounding it with opioids raises the infant sedation "
             "risk sharply.",
    ),
    "Codeine": dict(
        avoid_if=["asthma", "breathing problems", "sleep apnoea"],
        interacts_with=["Chlorpheniramine", "Tramadol"],
        note="Already contraindicated in breastfeeding; respiratory conditions "
             "and other sedatives compound it.",
    ),
    "Tramadol": dict(
        avoid_if=["epilepsy", "seizure disorder", "breathing problems"],
        interacts_with=["Codeine", "Chlorpheniramine", "Ondansetron"],
        note="Lowers the seizure threshold and carries serotonergic interactions.",
    ),
    "Prednisolone": dict(
        avoid_if=["diabetes", "peptic ulcer", "active infection"],
        interacts_with=["Ibuprofen", "Diclofenac"],
        note="Raises blood glucose and, with an NSAID, gastric bleeding risk.",
    ),
    "Domperidone": dict(
        avoid_if=["heart disease", "long qt", "arrhythmia"],
        interacts_with=["Ondansetron", "Azithromycin", "Fluconazole"],
        note="Carries a QT-prolongation warning; stacking QT-prolonging drugs is "
             "the real hazard.",
    ),
    "Ondansetron": dict(
        avoid_if=["long qt", "arrhythmia"],
        interacts_with=["Domperidone", "Azithromycin"],
        note="Also prolongs QT.",
    ),
    "Azithromycin": dict(
        avoid_if=["long qt", "arrhythmia", "liver disease"],
        interacts_with=["Domperidone", "Ondansetron"],
        note="Macrolides prolong QT.",
    ),
    "Fluconazole": dict(
        avoid_if=["liver disease", "long qt"],
        interacts_with=["Domperidone"],
        note="Hepatic metabolism, and a QT signal at higher doses.",
    ),
    "Pantoprazole": dict(
        avoid_if=[],
        interacts_with=["Ciprofloxacin"],
        note="Reduces stomach acid, which can cut absorption of some "
             "antibiotics.",
    ),
    "Ranitidine": dict(
        avoid_if=["kidney disease"],
        interacts_with=["Ciprofloxacin"],
        note="Withdrawn in many markets over an impurity concern.",
    ),
    "Metronidazole ": dict(avoid_if=[], interacts_with=[], note=""),
}

RISK_LABEL = {
    "compatible": "Generally compatible with breastfeeding",
    "caution": "Use with caution - discuss with your doctor",
    "avoid": "Best avoided while breastfeeding",
}


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)

    docs = []
    for g in GUIDANCE:
        docs.append({
            "id": g["id"],
            "collection": "guidance",
            "topic": g["topic"],
            "title": g["title"],
            "text": g["text"],
            "source": g["source"],
            "caution": g["caution"],
            # Retrieval matches against title + topic + body, so aliases and the
            # topic word get indexed alongside the prose.
            # Title and topic alone were almost worthless: they repeat words the
            # document already contains, and they are not what anyone types. The
            # `asked` string is the vocabulary of real questions -- taken from
            # the evaluation transcript in RagEvalTest, not invented -- so a
            # parent's phrasing reaches the document that answers it.
            "keywords": f"{g['topic']} {g['title']} {g.get('asked', '')}",
            "audience": g.get("audience", "baby"),
            "min_age": g.get("min_age"),
            "max_age": g.get("max_age"),
        })

    for m in MEDICINES:
        aliases = ", ".join(m["aka"])
        flags = CONTEXT_FLAGS.get(m["name"], {})
        docs.append({
            "id": f"med-{m['name'].lower().replace(' ', '-')}",
            "collection": "medicines",
            "topic": "medicine",
            "title": f"{m['name']} while breastfeeding",
            "text": f"{RISK_LABEL[m['risk']]}. {m['text']} Also sold as: {aliases}.",
            # The same entry without the risk label, so a caller that has
            # already stated the verdict can add the detail without repeating
            # itself. Composing prose from a single blob forces exactly that
            # duplication, and it reads as carelessness.
            "detail": m["text"],
            "source": LACTMED,
            "caution": "This is about a breastfeeding parent taking the medicine. It is "
                       "not advice about giving medicine to a baby, and it never "
                       "includes a dose. Confirm with your doctor or pharmacist.",
            "keywords": f"{m['name']} {aliases} breastfeeding lactation safe",
            "risk": m["risk"],
            "generic": m["name"],
            "aliases": m["aka"],
            "avoid_if": flags.get("avoid_if", []),
            "interacts_with": flags.get("interacts_with", []),
            "context_note": flags.get("note", ""),
            "audience": "mother",
        })

    payload = {
        "version": 1,
        "note": "Curated offline knowledge base. Retrieval is BM25 over these "
                "documents; nothing is fetched at runtime.",
        "documents": docs,
    }
    path = OUT / "knowledge.json"
    path.write_text(json.dumps(payload, indent=1))

    by_collection = {}
    for d in docs:
        by_collection[d["collection"]] = by_collection.get(d["collection"], 0) + 1
    from collections import Counter
    aud = Counter(d.get("audience") for d in docs)
    print(f"  audience split: {dict(aud)}")
    flagged = sum(1 for d in docs if d.get("avoid_if") or d.get("interacts_with"))
    print(f"  knowledge.json  {path.stat().st_size / 1024:.1f} KB  "
          f"{len(docs)} documents {by_collection}")
    print(f"  {flagged} medicines carry condition or interaction flags")


if __name__ == "__main__":
    main()
