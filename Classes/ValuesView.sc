// ValuesView: a wrapper to make custom widgets
// that hold an array of mapped 'values' and normalized 'inputs',
// and draws custom layers in a UserView, with mouse/arrow key interaction

ValuesView : View {
	// to be set and accessed by subclasses
	var <specs, <values, <inputs, <action, <wrap;

	var <>limitRefresh = false, <maxRefreshRate=25, updateWait, allowUpdate=true, updateHeld=false;
	var <>suppressRepeatedAction = true;
	var <>autoRefresh=true;          // refresh automatically when layer properties are updated
	var <>broadcastNewOnly = false;  // only notify dependents on input/value update if new value, otherwise anytime value is set

	// interaction
	var mouseDownPnt, mouseUpPnt, mouseMovePnt;
	var <>mouseDownAction, <>mouseUpAction, <>mouseMoveAction;
	var <>valuesPerPixel;

	var <userView;
	var <layers;   // array of drawing layers which respond to .properties

	// number of values inferred from number of intiVals
	*new { |parent, bounds, specs, initVals |
		^super.new(parent, bounds).superInit(specs, initVals);
	}

	superInit { |argSpecs, initVals|
		var numVals, numSpecs;

		// initVals are how we know how many values this view uses
		// this makes more sense than requiring specs and initializing
		// to their default values
		initVals ?? { Error("No initial values provided to ValuesView").throw };
		numVals = initVals.size;
		specs = argSpecs ?? numVals.collect{ \unipolar.asSpec.copy };
        numSpecs = specs.size;

        // if there are less specs than initVals, it's
        // assumed the values wrap around the spec list
		values = initVals.collect{ |val,i| val ?? { specs[i%numSpecs].default } };
		inputs = values.collect{ |v,i| specs[i % numSpecs].unmap(v) };
		action = { };
		wrap = numVals.collect{ false };
		valuesPerPixel = specs.collect{ |spec| spec.range / 200 }; // for interaction: movement range in pixels to cover full spec range
		updateWait = maxRefreshRate.reciprocal;

		userView = UserView(this, this.bounds.origin_(0@0)).resize_(5);
		userView.drawFunc_(this.drawFunc);

		// over/write mouse actions

		userView.mouseMoveAction_({
			|v,x,y,modifiers|
			mouseMovePnt = x@y;
			mouseMoveAction.(v,x,y,modifiers)
		});

		userView.mouseDownAction_({
			|v,x,y, modifiers, buttonNumber, clickCount|
			mouseDownPnt = x@y;
			mouseDownAction.(v,x,y, modifiers, buttonNumber, clickCount)
		});

		userView.mouseUpAction_({
			|v,x,y, modifiers|
			mouseUpPnt = x@y;
			mouseUpAction.(v,x,y,modifiers)
		});

		this.onResize_({ userView.bounds_(this.bounds.origin_(0@0)) });
		this.onClose_({ }); // set default onClose to removeDependants
	}

	// overwrite default View method to retain freeing dependants
	onClose_ { |func|
		var newFunc = {
			|...args|
			layers.do(_.removeDependant(this));
			func.(*args)
		 };
		// from View:onClose_
		this.manageFunctionConnection( onClose, newFunc, 'destroyed()', false );
		onClose = newFunc;
	}

	update { |changer, what ...args|
		// refresh when layer properties change
		if (what == \layerProperty) {
			autoRefresh.if{ this.refresh };
		}
	}

	drawFunc { this.subclassResponsibility(thisMethod) }

    // index is index of value list
	wrapAt_ { |index, bool| wrap[index] = bool }

	values_ { |...vals|
		var changed = (vals != values);
		vals.do{ |val, i|
			this.valueAt_(i, val, false);  // false: wait to broadcast all the changed values at once
		 };
		this.broadcastState(changed);
	}

	valueAt_ { |index, value, broadcast=true|
		var spec, oldValue;
		spec = specs[index];
		oldValue = values[index];
		values[index] = if (wrap[index]) {
			value.wrap(spec.minval, spec.maxval);
		} {
			spec.constrain(value);
		 };
		inputs[index] = spec.unmap(value);
		broadcast.if{ this.broadcastState(values[index]!=oldValue) };
	}

	valueAt { |index| ^values[index] }

	// set the value by unmapping a normalized value 0>1
	inputs_ { |...normInputs|
		var changed = (normInputs != inputs);
		normInputs.do{ |in, i|
			this.inputAt_(i, in, false);  // false: wait to broadcast all the changed values at once
		 };
		this.broadcastState(changed);
	}

	inputAt_ { |index, normInput, broadcast=true|
		var spec, oldValue;
		spec = specs[index];
		oldValue = values[index];

		inputs[index] = if (wrap[index]) {
			normInput.wrap(0,1);
		} {
			normInput.clip(0,1);
		 };
		values[index] = spec.map(inputs[index]);
		inputs[index] = spec.unmap(values[index]);
		broadcast.if{ this.broadcastState(values[index]!=oldValue) };
	}

	inputAt { |index| ^inputs[index] }

	valueAtAction_ { |index, value|
		var oldValue = values[index];
		this.valueAt_(index, value);
		this.doAction(oldValue!=values[index]);
	}

	inputAtAction_ { |index, normInput|
		var oldValue = inputs[index];
		this.input_(index, normInput);
		this.doAction(oldValue!=inputs[index]);
	}

	valuesAction_ { |...newValues|
		var changed = newValues.collect({ |nv, i| nv != values[i] }).any(_ == true);
		newValues.do({ |val, i| this.valueAt_(i, val, false) });
		this.broadcastState(changed);
		this.doAction(changed);
	}

	inputsAction_ { |...normInputs|
		var changed = normInputs.collect({ |ni, i| ni != inputs[i] }).any(_ == true);
		normInputs.do({ |in, i| this.inputAt_(i, in, false) });
		this.broadcastState(changed);
		this.doAction(changed);
	}


	broadcastState { |newValue = true|
		// update the value and input in layers' properties list
		// note: because this sets p values directly, it doesn't trigger an update
		layers.do{ |l| l.p.vals = values; l.p.inputs = inputs };

		// notify dependants
		if (newValue) {
			this.changed(\values, values);
			this.changed(\inputs, inputs);
			this.refresh; // TODO: consider making this a global flag, e.g. refreshNewOnly
		} {
			if (broadcastNewOnly.not) {
				this.changed(\values, values);
				this.changed(\inputs, inputs);
			}
		}
	}

	action_ { |actionFunc|
		action = actionFunc;
	}

	doAction { |newValue=true|
		if (suppressRepeatedAction.not or: newValue) {
			action.(this, values, inputs)
		 };
	}

	specAt_ { |index, controlSpec, updateValue=true|
		var rangeInPx;
		if (controlSpec.isKindOf(ControlSpec).not) {
			"Spec provided isn't a ControlSpec. Spec isn't updated.".warn;
			^this
		 };
		rangeInPx = specs[index].range / valuesPerPixel[index]; // get old pixels per range
		specs[index] = controlSpec;
		this.rangeInPixelsAt_(index, rangeInPx);                // restore mouse scaling so it feels the same
		updateValue.if{ this.valueAt_(index, values[index]) };    // also updates input
	}

	// refresh { userView.refresh }
	refresh {
		if (limitRefresh) {
			if (allowUpdate) {
				userView.refresh;
				allowUpdate = false;
				AppClock.sched(updateWait, {

					if (updateHeld) { // perform deferred refresh
						userView.refresh;
						updateHeld = false;
					 };
					allowUpdate = true;
				});
			} {
				updateHeld = true;
			 };
		} {
			userView.refresh;
		}
	}

	rangeInPixelsAt_ { |index, px|
		valuesPerPixel[index] = specs[index].range/px;
	}

	maxRefreshRate_ { |hz|
		maxRefreshRate = hz;
		updateWait = maxRefreshRate.reciprocal;
	}
}
